package com.simplerag.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentScannerTest {
    @TempDir Path temp;

    @Test
    void appliesTraversalPolicyWithoutReadingOrChunkingDocuments() throws Exception {
        Path root = Files.createDirectories(temp.resolve("knowledge"));
        Files.writeString(root.resolve("notes.md"), "included");
        Files.writeString(root.resolve("README"), "included without extension");
        Files.writeString(root.resolve("photo.png"), "ignored");
        Path ignored = Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(ignored.resolve("hidden.md"), "ignored directory");

        DocumentScanner.ScanResult result = new DocumentScanner().scan(List.of(root));

        assertEquals(List.of(root.toAbsolutePath().normalize()), result.roots());
        assertEquals(2, result.documents().size());
        assertTrue(result.documents().stream().allMatch(item -> item.root().equals(root.toAbsolutePath().normalize())));
    }
}
