package com.simplerag.common.crypto;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DigestsTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void hashesUtf8AndFilesWithTheSameStableSha256() throws Exception {
        String content = "SimpleRAG 公共摘要能力";
        Path file = temporaryDirectory.resolve("sample.txt");
        Files.writeString(file, content, StandardCharsets.UTF_8);

        String fromText = Digests.hex(Digests.sha256Utf8(content));
        String fromFile = Digests.sha256File(file);

        assertEquals(fromText, fromFile);
        assertEquals(64, fromFile.length());
    }
}
