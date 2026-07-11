package com.simplerag.search;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Registry boundary for document decoding; currently provides the existing plain-text reader. */
public final class DocumentReaderRegistry {
    public static final int READER_VERSION = 1;

    public ReadDocument read(Path file, Path root) throws IOException {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException invalidUtf8) {
            text = Files.readString(file, Charset.defaultCharset());
        }
        if (text.isBlank() || looksBinary(text)) return null;
        List<String> lines = Arrays.asList(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        long modified;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException ignored) {
            modified = 0;
        }
        return new ReadDocument(file.toAbsolutePath().normalize(), root, extension(file), lines, modified);
    }

    private static boolean looksBinary(String value) {
        int sample = Math.min(value.length(), 2048);
        int controls = 0;
        for (int i = 0; i < sample; i++) {
            char c = value.charAt(i);
            if (c == 0) return true;
            if (c < 9 || (c > 13 && c < 32)) controls++;
        }
        return sample > 0 && controls > sample / 20;
    }

    private static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "text";
    }

    public record ReadDocument(Path path, Path root, String extension, List<String> lines, long modifiedAt) {
        public ReadDocument { lines = List.copyOf(lines); }
    }
}
