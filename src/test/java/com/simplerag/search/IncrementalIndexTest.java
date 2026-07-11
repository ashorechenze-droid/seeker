package com.simplerag.search;

import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.model.DocumentChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IncrementalIndexTest {
    @TempDir Path temporaryDirectory;

    @Test
    void onlyChangedFileIsEmbeddedAndDeletedChunksDisappear() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path changed = source.resolve("changed.txt");
        Path reused = source.resolve("reused.txt");
        Files.writeString(changed, "first version contains enough searchable text");
        Files.writeString(reused, "unchanged document contains reusable vector data");
        CountingEmbedder embedder = new CountingEmbedder("model-a");
        IndexSnapshot first = buildSnapshot(source, embedder, null, 1);
        float[] reusedVector = first.chunks().stream().filter(chunk -> chunk.fileName().equals("reused.txt"))
                .findFirst().orElseThrow().embedding().clone();

        embedder.clear();
        Files.writeString(changed, "second version has different searchable content for incremental indexing");
        IndexSnapshot second = buildSnapshot(source, embedder, first, 2);

        assertFalse(embedder.texts.isEmpty());
        assertTrue(embedder.texts.stream().allMatch(text -> text.startsWith("changed.txt\n")));
        assertArrayEquals(reusedVector, second.chunks().stream()
                .filter(chunk -> chunk.fileName().equals("reused.txt")).findFirst().orElseThrow().embedding());

        embedder.clear();
        Files.delete(reused);
        IndexSnapshot third = buildSnapshot(source, embedder, second, 3);
        assertTrue(embedder.texts.isEmpty(), "deleting a file must not re-embed unchanged files");
        assertTrue(third.chunks().stream().noneMatch(chunk -> chunk.fileName().equals("reused.txt")));
        assertTrue(third.documentEntries().stream().noneMatch(entry -> entry.relativePath().equals("reused.txt")));
    }

    @Test
    void modelVersionChangeRebuildsEveryChunk() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("model-source"));
        Files.writeString(source.resolve("one.txt"), "first document has enough content to create a chunk");
        Files.writeString(source.resolve("two.txt"), "second document also has enough content for indexing");
        IndexSnapshot previous = buildSnapshot(source, new CountingEmbedder("model-a"), null, 1);
        CountingEmbedder changedModel = new CountingEmbedder("model-b");

        IndexSnapshot rebuilt = buildSnapshot(source, changedModel, previous, 2);

        assertEquals(rebuilt.chunks().size(), changedModel.texts.size());
        assertEquals(2, changedModel.texts.stream().map(text -> text.substring(0, text.indexOf('\n'))).distinct().count());
    }

    @Test
    void plannerTreatsReaderAndChunkingVersionChangesAsModified() {
        FileFingerprint fingerprint = new FileFingerprint("root", "note.txt", 12, 34, "hash");
        DocumentIndexEntry previous = DocumentIndexEntry.from(fingerprint, 1, 1, List.of("chunk"));
        IncrementalIndexPlanner planner = new IncrementalIndexPlanner();

        assertEquals(List.of(fingerprint), planner.plan(List.of(previous), List.of(fingerprint),
                2, 1, true).modified());
        assertEquals(List.of(fingerprint), planner.plan(List.of(previous), List.of(fingerprint),
                1, 2, true).modified());
        assertEquals(List.of(fingerprint), planner.plan(List.of(previous), List.of(fingerprint),
                1, 1, false).modified());
    }

    @Test
    void plannerClassifiesAddedModifiedDeletedAndReusedByContentIdentity() {
        FileFingerprint reused = new FileFingerprint("root", "reused.txt", 10, 20, "same");
        FileFingerprint oldModified = new FileFingerprint("root", "modified.txt", 12, 30, "old-hash");
        FileFingerprint modifiedWithSameMetadata = new FileFingerprint("root", "modified.txt", 12, 30, "new-hash");
        FileFingerprint deleted = new FileFingerprint("root", "deleted.txt", 14, 40, "deleted");
        FileFingerprint added = new FileFingerprint("root", "added.txt", 16, 50, "added");
        List<DocumentIndexEntry> previous = List.of(
                DocumentIndexEntry.from(reused, 1, 1, List.of("reused-chunk")),
                DocumentIndexEntry.from(oldModified, 1, 1, List.of("old-chunk")),
                DocumentIndexEntry.from(deleted, 1, 1, List.of("deleted-chunk")));

        IncrementalIndexPlan plan = new IncrementalIndexPlanner().plan(previous,
                List.of(reused, modifiedWithSameMetadata, added), 1, 1, true);

        assertEquals(List.of(added), plan.added());
        assertEquals(List.of(modifiedWithSameMetadata), plan.modified());
        assertEquals(List.of("deleted.txt"), plan.deleted().stream().map(DocumentIndexEntry::relativePath).toList());
        assertEquals(List.of("reused.txt"), plan.reused().stream().map(DocumentIndexEntry::relativePath).toList());
    }

    @Test
    void incrementalSnapshotMatchesFullRebuild() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("equivalence-source"));
        Path first = source.resolve("first.txt");
        Path deleted = source.resolve("deleted.txt");
        Files.writeString(first, "original first document with stable reusable chunks");
        Files.writeString(deleted, "this document will be deleted before the next revision");
        IndexSnapshot previous = buildSnapshot(source, new CountingEmbedder("model-a"), null, 1);

        Files.writeString(first, "modified first document with new incremental content");
        Files.delete(deleted);
        Files.writeString(source.resolve("added.txt"), "newly added document for equivalence validation");
        IndexSnapshot incremental = buildSnapshot(source, new CountingEmbedder("model-a"), previous, 2);
        IndexSnapshot full = buildSnapshot(source, new CountingEmbedder("model-a"), null, 2);

        assertEquals(sortedEntries(full), sortedEntries(incremental));
        List<DocumentChunk> expected = sortedChunks(full);
        List<DocumentChunk> actual = sortedChunks(incremental);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) assertChunkEquals(expected.get(i), actual.get(i));
    }

    private IndexSnapshot buildSnapshot(Path source, CountingEmbedder embedder,
                                        IndexSnapshot previous, long revision) throws Exception {
        SemanticSearchEngine engine = new SemanticSearchEngine(embedder);
        engine.index(List.of(source), previous, null);
        IndexSnapshot raw = engine.snapshot();
        int dimension = raw.chunks().stream().filter(DocumentChunk::hasEmbedding)
                .mapToInt(chunk -> chunk.embedding().length).findFirst().orElse(0);
        IndexManifest manifest = new IndexManifest("kb", revision, IndexIdentity.sourceSetHash(List.of(source)),
                dimension == 0 ? "" : embedder.signature().value(), dimension,
                IndexIdentity.CHUNKING_VERSION, IndexSnapshot.CURRENT_VERSION, System.currentTimeMillis());
        return engine.snapshot(manifest);
    }

    private static List<DocumentIndexEntry> sortedEntries(IndexSnapshot snapshot) {
        return snapshot.documentEntries().stream().sorted(Comparator.comparing(DocumentIndexEntry::key)).toList();
    }

    private static List<DocumentChunk> sortedChunks(IndexSnapshot snapshot) {
        return snapshot.chunks().stream().sorted(Comparator.comparing(DocumentChunk::id)).toList();
    }

    private static void assertChunkEquals(DocumentChunk expected, DocumentChunk actual) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.path(), actual.path());
        assertEquals(expected.root(), actual.root());
        assertEquals(expected.fileName(), actual.fileName());
        assertEquals(expected.extension(), actual.extension());
        assertEquals(expected.startLine(), actual.startLine());
        assertEquals(expected.endLine(), actual.endLine());
        assertEquals(expected.content(), actual.content());
        assertEquals(expected.modifiedAt(), actual.modifiedAt());
        assertArrayEquals(expected.embedding(), actual.embedding());
    }

    private static final class CountingEmbedder implements TextEmbedder {
        private final String model;
        private final List<String> texts = new ArrayList<>();

        private CountingEmbedder(String model) { this.model = model; }
        private void clear() { texts.clear(); }
        @Override public boolean isConfigured() { return true; }
        @Override public List<float[]> embed(List<String> values) {
            texts.addAll(values);
            return values.stream().map(CountingEmbedder::vector).toList();
        }
        private static float[] vector(String text) {
            return new float[]{text.length(), text.hashCode(), 1.0f};
        }
        @Override public String modelName() { return model; }
        @Override public String status() { return "ready"; }
        @Override public int dimension() { return 3; }
        @Override public void close() { }
    }
}
