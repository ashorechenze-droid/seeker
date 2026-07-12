package com.simplerag.search.reader;

import com.simplerag.search.DocumentReader;
import com.simplerag.search.DocumentSection;
import com.simplerag.search.DocumentTextUnit;
import com.simplerag.search.ReadDocument;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class PlainTextDocumentReader implements DocumentReader {
    public static final int VERSION = 1;
    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024;
    private static final Set<String> EXTENSIONS = Set.of(
            "md", "markdown", "txt", "rst", "adoc", "java", "kt", "kts", "py", "js", "jsx",
            "ts", "tsx", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "rb", "swift",
            "scala", "sql", "sh", "bash", "ps1", "bat", "cmd", "css", "scss", "less", "xml",
            "json", "jsonl", "yaml", "yml", "toml", "ini", "properties", "conf", "vue", "svelte",
            "gradle", "dockerfile");
    private static final Set<String> FILE_NAMES = Set.of("dockerfile", "makefile", "readme", "license");

    @Override public String id() { return "plain-text"; }
    @Override public int version() { return VERSION; }
    @Override public Set<String> extensions() { return EXTENSIONS; }
    @Override public Set<String> fileNames() { return FILE_NAMES; }
    @Override public long maxFileSizeBytes() { return MAX_FILE_SIZE; }

    @Override
    public ReadDocument read(Path file, Path root) throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException invalidUtf8) {
            text = Files.readString(file, Charset.defaultCharset());
        }
        if (text.isBlank() || looksBinary(text)) return null;
        List<DocumentTextUnit> units = ReaderSupport.lineUnits(text);
        return ReaderSupport.document(file, root, id(), version(),
                List.of(new DocumentSection("text", "", "", "L", units)), List.of());
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
