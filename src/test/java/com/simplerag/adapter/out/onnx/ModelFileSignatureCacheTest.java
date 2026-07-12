package com.simplerag.adapter.out.onnx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelFileSignatureCacheTest {
    @TempDir Path temporary;

    @Test void cachesStableFilesAndInvalidatesWhenMetadataChanges() throws Exception {
        Path model = temporary.resolve("model.onnx");
        Files.writeString(model, "first");
        Path cacheFile = temporary.resolve("cache/signatures.json");
        ModelFileSignatureCache cache = new ModelFileSignatureCache(cacheFile);
        String first = cache.signature(List.of(model));
        String second = cache.signature(List.of(model));
        assertEquals(first, second);
        assertTrue(Files.readString(cacheFile).contains("sha256"));

        Files.writeString(model, "second-and-longer");
        assertNotEquals(first, cache.signature(List.of(model)));
    }
}
