package com.simplerag.search;

import com.simplerag.model.DocumentChunk;
import com.simplerag.probe.UnexpectedGadget;
import com.simplerag.search.reader.PlainTextDocumentReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Snapshot files are attacker-writable local state, so loading them must be filtered. */
class IndexStoreSecurityTest {
    @TempDir Path temporaryDirectory;

    @BeforeEach
    void resetProbe() {
        UnexpectedGadget.reset();
    }

    @Test
    void snapshotWithEmbeddingsAndManifestStillRoundTrips() throws Exception {
        IndexStore store = IndexStore.versioned(temporaryDirectory, "kb");
        IndexSnapshot original = snapshot(7);

        store.saveRevision(original);
        Optional<IndexSnapshot> loaded = store.loadRevision(7);

        assertTrue(loaded.isPresent(), "允许清单必须覆盖快照自身的对象图");
        IndexSnapshot actual = loaded.get();
        assertEquals(original.roots(), actual.roots());
        assertEquals(original.documentEntries(), actual.documentEntries());
        assertEquals(1, actual.chunks().size());
        assertEquals(original.chunks().get(0).id(), actual.chunks().get(0).id());
        assertArrayEquals(original.chunks().get(0).embedding(), actual.chunks().get(0).embedding());
        assertEquals(original.manifest(), actual.manifest());
    }

    @Test
    void classesOutsideTheSnapshotGraphAreRejectedBeforeTheirCodeRuns() throws Exception {
        IndexStore store = IndexStore.versioned(temporaryDirectory, "kb");
        Path revision = temporaryDirectory.resolve("kb").resolve("9.bin");
        Files.createDirectories(revision.getParent());
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(revision))) {
            output.writeObject(new UnexpectedGadget("payload"));
        }

        Optional<IndexSnapshot> loaded = store.loadRevision(9);

        assertTrue(loaded.isEmpty(), "非白名单负载必须被当作索引缺失");
        assertFalse(UnexpectedGadget.deserialized,
                "过滤器必须在 readObject 执行之前拒绝该类，否则 gadget chain 仍会运行");
    }

    @Test
    void corruptRevisionIsTreatedAsMissingIndex() throws Exception {
        IndexStore store = IndexStore.versioned(temporaryDirectory, "kb");
        Path revision = temporaryDirectory.resolve("kb").resolve("11.bin");
        Files.createDirectories(revision.getParent());
        Files.write(revision, "这不是一个序列化流".getBytes(StandardCharsets.UTF_8));

        assertTrue(store.loadRevision(11).isEmpty());
    }

    private static IndexSnapshot snapshot(long revision) {
        DocumentChunk chunk = new DocumentChunk("chunk-1", "C:/kb/notes.md", "C:/kb", "notes.md", "md",
                1, 4, "L1-4", "索引快照往返测试内容", 1_700_000_000_000L, new float[]{0.25f, -0.5f, 1.0f});
        DocumentIndexEntry entry = new DocumentIndexEntry("C:/kb", "notes.md", 128L, 1_700_000_000_000L,
                "abc123", "plain-text", PlainTextDocumentReader.VERSION, ChunkerRegistry.CHUNKING_VERSION,
                List.of("chunk-1"));
        IndexManifest manifest = new IndexManifest("kb", revision, "source-set-hash", "signature", 3,
                ChunkerRegistry.CHUNKING_VERSION, IndexSnapshot.CURRENT_VERSION, 1_700_000_000_000L);
        return new IndexSnapshot(IndexSnapshot.CURRENT_VERSION, List.of("C:/kb"), List.of(chunk),
                1_700_000_000_000L, "signature", manifest, List.of(entry));
    }
}
