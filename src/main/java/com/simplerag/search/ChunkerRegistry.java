package com.simplerag.search;

import com.simplerag.model.DocumentChunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Selects the existing code-window or prose-paragraph chunking strategy by document type. */
public final class ChunkerRegistry {
    public static final int CHUNKING_VERSION = 1;

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "py", "js", "jsx", "ts", "tsx", "go", "rs", "c", "h",
            "cpp", "hpp", "cs", "php", "rb", "swift", "scala", "sql", "sh", "bash", "ps1",
            "bat", "cmd", "html", "css", "scss", "vue", "svelte"
    );

    public List<DocumentChunk> chunk(DocumentReaderRegistry.ReadDocument document) {
        return CODE_EXTENSIONS.contains(document.extension())
                ? chunkByWindow(document, 36, 8) : chunkByParagraph(document);
    }

    private static List<DocumentChunk> chunkByWindow(DocumentReaderRegistry.ReadDocument document,
                                                       int window, int overlap) {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (int start = 0; start < document.lines().size(); start += window - overlap) {
            int end = Math.min(document.lines().size(), start + window);
            addChunk(chunks, document, start, end);
            if (end == document.lines().size()) break;
        }
        return chunks;
    }

    private static List<DocumentChunk> chunkByParagraph(DocumentReaderRegistry.ReadDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int start = 0;
        int characters = 0;
        for (int i = 0; i < document.lines().size(); i++) {
            characters += document.lines().get(i).length() + 1;
            boolean boundary = document.lines().get(i).isBlank() && characters >= 280;
            boolean full = characters >= 1400;
            if (boundary || full) {
                addChunk(chunks, document, start, i + 1);
                start = i + 1;
                characters = 0;
            }
        }
        if (start < document.lines().size()) addChunk(chunks, document, start, document.lines().size());
        return chunks;
    }

    private static void addChunk(List<DocumentChunk> chunks, DocumentReaderRegistry.ReadDocument document,
                                 int start, int end) {
        while (start < end && document.lines().get(start).isBlank()) start++;
        while (end > start && document.lines().get(end - 1).isBlank()) end--;
        if (start >= end) return;
        String content = String.join("\n", document.lines().subList(start, end)).strip();
        if (content.length() < 12) return;
        String id = sha1(document.path() + ":" + (start + 1) + ":" + content);
        chunks.add(new DocumentChunk(id, document.path().toString(), document.root().toString(),
                document.path().getFileName().toString(), document.extension(), start + 1, end,
                content, document.modifiedAt(), null));
    }

    private static String sha1(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-1").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
