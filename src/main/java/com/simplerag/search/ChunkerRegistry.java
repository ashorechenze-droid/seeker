package com.simplerag.search;

import com.simplerag.model.DocumentChunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Selects code-window or prose-paragraph chunking while preserving source section locations. */
public final class ChunkerRegistry {
    public static final int CHUNKING_VERSION = 2;

    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "py", "js", "jsx", "ts", "tsx", "go", "rs", "c", "h",
            "cpp", "hpp", "cs", "php", "rb", "swift", "scala", "sql", "sh", "bash", "ps1",
            "bat", "cmd", "css", "scss", "vue", "svelte",
            "lua", "r", "pl", "pm", "ex", "exs", "erl", "dart", "groovy",
            "proto", "graphql", "gql", "tf", "tfvars", "hcl");

    public List<DocumentChunk> chunk(ReadDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        for (DocumentSection section : document.sections()) {
            if (CODE_EXTENSIONS.contains(document.extension())) {
                chunkByWindow(chunks, document, section, 36, 8);
            } else {
                chunkByParagraph(chunks, document, section);
            }
        }
        return List.copyOf(chunks);
    }

    private static void chunkByWindow(List<DocumentChunk> chunks, ReadDocument document,
                                      DocumentSection section, int window, int overlap) {
        for (int start = 0; start < section.units().size(); start += window - overlap) {
            int end = Math.min(section.units().size(), start + window);
            addChunk(chunks, document, section, start, end);
            if (end == section.units().size()) break;
        }
    }

    private static void chunkByParagraph(List<DocumentChunk> chunks, ReadDocument document,
                                         DocumentSection section) {
        int start = 0;
        int characters = 0;
        for (int index = 0; index < section.units().size(); index++) {
            characters += section.units().get(index).text().length() + 1;
            boolean boundary = section.units().get(index).text().isBlank() && characters >= 280;
            boolean full = characters >= 1400;
            if (boundary || full) {
                addChunk(chunks, document, section, start, index + 1);
                start = index + 1;
                characters = 0;
            }
        }
        if (start < section.units().size()) addChunk(chunks, document, section, start, section.units().size());
    }

    private static void addChunk(List<DocumentChunk> chunks, ReadDocument document, DocumentSection section,
                                 int start, int end) {
        while (start < end && section.units().get(start).text().isBlank()) start++;
        while (end > start && section.units().get(end - 1).text().isBlank()) end--;
        if (start >= end) return;
        String content = String.join("\n", section.units().subList(start, end).stream()
                .map(DocumentTextUnit::text).toList()).strip();
        if (content.length() < 12) return;
        int startUnit = section.units().get(start).number();
        int endUnit = section.units().get(end - 1).number();
        String sourceLocation = section.sourceLocation(start, end);
        String id = sha1(document.documentId() + ":" + section.id() + ":" + sourceLocation + ":" + content);
        chunks.add(new DocumentChunk(id, document.path().toString(), document.root().toString(),
                document.path().getFileName().toString(), document.extension(), startUnit, endUnit,
                sourceLocation, content, document.modifiedAt(), null));
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
