package com.simplerag.search.reader;

import com.simplerag.search.DocumentSection;
import com.simplerag.search.DocumentTextUnit;
import com.simplerag.search.ReadDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class ReaderSupport {
    private ReaderSupport() { }

    static ReadDocument document(Path file, Path root, String readerId, int readerVersion,
                                 List<DocumentSection> sections, List<String> warnings) throws IOException {
        Path normalizedFile = file.toAbsolutePath().normalize();
        Path normalizedRoot = root.toAbsolutePath().normalize();
        String identity = normalizedFile.startsWith(normalizedRoot)
                ? normalizedRoot + "\0" + normalizedRoot.relativize(normalizedFile).toString().replace('\\', '/')
                : normalizedFile.toString();
        long modifiedAt;
        try {
            modifiedAt = Files.getLastModifiedTime(normalizedFile).toMillis();
        } catch (IOException ignored) {
            modifiedAt = 0;
        }
        return new ReadDocument(normalizedFile, normalizedRoot, identity, extension(normalizedFile), sections,
                modifiedAt, readerId, readerVersion, warnings);
    }

    static List<DocumentTextUnit> lineUnits(String text) {
        String normalized = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.isBlank()) return List.of();
        String[] lines = normalized.split("\n", -1);
        List<DocumentTextUnit> result = new ArrayList<>(lines.length);
        for (int index = 0; index < lines.length; index++) {
            result.add(new DocumentTextUnit(index + 1, lines[index].stripTrailing()));
        }
        return List.copyOf(result);
    }

    static String normalize(String value) {
        return value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    static String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "text";
    }

    static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message.strip();
    }
}
