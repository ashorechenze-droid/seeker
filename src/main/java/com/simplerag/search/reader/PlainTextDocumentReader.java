package com.simplerag.search.reader;

import com.simplerag.search.DocumentReader;
import com.simplerag.search.DocumentSection;
import com.simplerag.search.DocumentTextUnit;
import com.simplerag.search.ReadDocument;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class PlainTextDocumentReader implements DocumentReader {
    /** Bumped to 2 when BOM stripping and UTF-16 detection changed the extracted text. */
    public static final int VERSION = 2;
    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of(
            "md", "markdown", "txt", "rst", "adoc", "java", "kt", "kts", "py", "js", "jsx",
            "ts", "tsx", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "rb", "swift",
            "scala", "sql", "sh", "bash", "ps1", "bat", "cmd", "css", "scss", "less", "xml",
            "json", "jsonl", "yaml", "yml", "toml", "ini", "properties", "conf", "vue", "svelte",
            "gradle", "dockerfile",
            "csv", "tsv", "proto", "graphql", "gql", "tf", "tfvars", "hcl",
            "lua", "r", "pl", "pm", "ex", "exs", "erl", "dart", "groovy");
    private static final Set<String> FILE_NAMES = Set.of("dockerfile", "makefile", "readme", "license",
            "gemfile", "rakefile", "procfile", "vagrantfile", "justfile", "taskfile");

    @Override public String id() { return "plain-text"; }
    @Override public int version() { return VERSION; }
    @Override public Set<String> extensions() { return EXTENSIONS; }
    @Override public Set<String> fileNames() { return FILE_NAMES; }
    @Override public long maxFileSizeBytes() { return MAX_FILE_SIZE; }

    @Override
    public ReadDocument read(Path file, Path root) throws IOException {
        String text = decode(Files.readAllBytes(file));
        if (text.isBlank() || looksBinary(text)) return null;
        List<DocumentTextUnit> units = ReaderSupport.lineUnits(text);
        return ReaderSupport.document(file, root, id(), version(),
                List.of(new DocumentSection("text", "", "", "L", units)), List.of());
    }

    /**
     * Decodes with an explicit byte-order mark when present, otherwise strict UTF-8 with a platform
     * fallback. Without BOM handling a UTF-8 signature leaks {@code U+FEFF} into the first token and
     * UTF-16 files decode into control characters that {@link #looksBinary} then discards.
     */
    private static String decode(byte[] bytes) {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) return decode(bytes, 3, StandardCharsets.UTF_8);
        if (startsWith(bytes, 0xFF, 0xFE)) return decode(bytes, 2, StandardCharsets.UTF_16LE);
        if (startsWith(bytes, 0xFE, 0xFF)) return decode(bytes, 2, StandardCharsets.UTF_16BE);
        try {
            return strict(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException invalidUtf8) {
            return decode(bytes, 0, Charset.defaultCharset());
        }
    }

    private static String decode(byte[] bytes, int offset, Charset charset) {
        return new String(bytes, offset, bytes.length - offset, charset);
    }

    private static String strict(byte[] bytes, Charset charset) throws CharacterCodingException {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static boolean startsWith(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if ((bytes[index] & 0xFF) != signature[index]) return false;
        }
        return true;
    }

    private static boolean looksBinary(String value) {
        int sample = Math.min(value.length(), 2048);
        int controls = 0;
        for (int index = 0; index < sample; index++) {
            char character = value.charAt(index);
            if (character == 0) return true;
            if (character < 9 || (character > 13 && character < 32)) controls++;
        }
        return sample > 0 && controls > sample / 20;
    }
}
