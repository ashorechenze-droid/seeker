package com.simplerag.search;

import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.model.DocumentChunk;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Builds a complete new snapshot while reusing unchanged chunks and embeddings from a published snapshot. */
public final class IncrementalIndexBuilder {
    private static final int EMBEDDING_BATCH_SIZE = 24;

    private final TextEmbedder embedder;
    private final DocumentScanner scanner;
    private final DocumentReaderRegistry readers;
    private final ChunkerRegistry chunkers;
    private final IncrementalIndexPlanner planner;

    public IncrementalIndexBuilder(TextEmbedder embedder, DocumentScanner scanner,
                                   DocumentReaderRegistry readers, ChunkerRegistry chunkers) {
        this(embedder, scanner, readers, chunkers, new IncrementalIndexPlanner());
    }

    public IncrementalIndexBuilder(TextEmbedder embedder, DocumentScanner scanner,
                                   DocumentReaderRegistry readers, ChunkerRegistry chunkers,
                                   IncrementalIndexPlanner planner) {
        this.embedder = embedder;
        this.scanner = scanner;
        this.readers = readers;
        this.chunkers = chunkers;
        this.planner = planner;
    }

    public BuildResult build(List<Path> sourceRoots, IndexSnapshot previous,
                             Consumer<SemanticSearchEngine.IndexProgress> progress) throws IOException {
        long started = System.nanoTime();
        DocumentScanner.ScanResult scan = scanner.scan(sourceRoots, readers);
        long scannedAt = System.nanoTime();
        List<FileFingerprint> fingerprints = new ArrayList<>();
        List<IndexableDocument> currentDocuments = new ArrayList<>();
        Map<String, DocumentScanner.ScannedDocument> documentsByKey = new LinkedHashMap<>();
        List<IndexBuildWarning> warnings = new ArrayList<>(scan.warnings());
        int skipped = (int) scan.warnings().stream().filter(IndexBuildWarning::skipped).count();
        for (DocumentScanner.ScannedDocument document : scan.documents()) {
            checkInterrupted();
            try {
                FileFingerprint fingerprint = FileFingerprint.capture(document);
                fingerprints.add(fingerprint);
                currentDocuments.add(new IndexableDocument(fingerprint, document.readerId(), document.readerVersion()));
                documentsByKey.put(fingerprint.key(), document);
            } catch (IOException | RuntimeException unreadable) {
                skipped++;
                warnings.add(new IndexBuildWarning(document.path(), document.readerId(),
                        "无法读取文件元数据：" + message(unreadable), true));
            }
        }

        List<DocumentIndexEntry> previousEntries = previous == null
                ? List.of() : previous.documentEntries();
        boolean reuseCompatible = reuseCompatible(previous);
        IncrementalIndexPlan plan = planner.plan(previousEntries, currentDocuments,
                ChunkerRegistry.CHUNKING_VERSION, reuseCompatible);
        Map<String, DocumentIndexEntry> reusableEntries = new HashMap<>();
        plan.reused().forEach(entry -> reusableEntries.put(entry.key(), entry));
        Map<String, DocumentChunk> previousChunks = new HashMap<>();
        if (previous != null) previous.chunks().forEach(chunk -> previousChunks.put(chunk.id(), chunk));

        List<FileWork> files = new ArrayList<>();
        int processed = 0;
        for (FileFingerprint fingerprint : fingerprints) {
            checkInterrupted();
            DocumentScanner.ScannedDocument scanned = documentsByKey.get(fingerprint.key());
            DocumentIndexEntry reusable = reusableEntries.get(fingerprint.key());
            List<DocumentChunk> chunks = reusable == null ? null : reusableChunks(reusable, previousChunks);
            if (chunks == null) {
                try {
                    ReadDocument read = readers.read(scanned.path(), scanned.root());
                    chunks = read == null ? List.of() : chunkers.chunk(read);
                    IndexableDocument current = new IndexableDocument(fingerprint,
                            scanned.readerId(), scanned.readerVersion());
                    DocumentIndexEntry entry = DocumentIndexEntry.from(current, ChunkerRegistry.CHUNKING_VERSION,
                            chunks.stream().map(DocumentChunk::id).toList());
                    files.add(new FileWork(entry, chunks, true));
                    if (read != null) {
                        read.warnings().forEach(warning -> warnings.add(new IndexBuildWarning(
                                scanned.path(), scanned.readerId(), warning, false)));
                    }
                } catch (IOException | RuntimeException unreadable) {
                    skipped++;
                    warnings.add(new IndexBuildWarning(scanned.path(), scanned.readerId(),
                            message(unreadable), true));
                }
            } else {
                files.add(new FileWork(reusable, chunks, false));
            }
            processed++;
            if (progress != null && (processed == fingerprints.size() || processed % 10 == 0)) {
                progress.accept(new SemanticSearchEngine.IndexProgress(processed, fingerprints.size(),
                        scanned.path(), "扫描"));
            }
        }

        long readAt = System.nanoTime();
        Map<String, DocumentChunk> embeddedNewChunks = embedNewChunks(files, progress);
        long embeddedAt = System.nanoTime();
        List<DocumentChunk> completeChunks = new ArrayList<>();
        List<DocumentIndexEntry> completeEntries = new ArrayList<>();
        for (FileWork file : files) {
            completeEntries.add(file.entry());
            for (DocumentChunk chunk : file.chunks()) {
                completeChunks.add(file.requiresEmbedding()
                        ? embeddedNewChunks.getOrDefault(chunk.id(), chunk) : chunk);
            }
        }
        long completedAt = System.nanoTime();
        Map<String, Long> timings = Map.of(
                "scan", millis(started, scannedAt),
                "readAndChunk", millis(scannedAt, readAt),
                "embedding", millis(readAt, embeddedAt),
                "assemble", millis(embeddedAt, completedAt),
                "total", millis(started, completedAt));
        return new BuildResult(scan.roots().stream().map(Path::toString).toList(), completeChunks,
                completeEntries, new SemanticSearchEngine.IndexReport(files.size(), completeChunks.size(), skipped,
                warnings, timings), plan);
    }

    private Map<String, DocumentChunk> embedNewChunks(List<FileWork> files,
                                                       Consumer<SemanticSearchEngine.IndexProgress> progress)
            throws IOException {
        List<DocumentChunk> pending = files.stream().filter(FileWork::requiresEmbedding)
                .flatMap(file -> file.chunks().stream()).toList();
        if (!embedder.isConfigured() || pending.isEmpty()) return Map.of();
        Map<String, DocumentChunk> embedded = new HashMap<>();
        for (int start = 0; start < pending.size(); start += EMBEDDING_BATCH_SIZE) {
            checkInterrupted();
            int end = Math.min(pending.size(), start + EMBEDDING_BATCH_SIZE);
            List<DocumentChunk> batch = pending.subList(start, end);
            List<float[]> vectors;
            try {
                vectors = embedder.embed(batch.stream()
                        .map(chunk -> chunk.fileName() + "\n" + chunk.content()).toList());
            } catch (IOException failure) {
                throw new IOException("生成本地语义向量失败：" + failure.getMessage(), failure);
            }
            if (vectors.size() != batch.size()) throw new IOException("本地语义模型返回的向量数量不正确");
            for (int i = 0; i < batch.size(); i++) {
                DocumentChunk chunk = batch.get(i);
                embedded.put(chunk.id(), chunk.withEmbedding(vectors.get(i)));
            }
            if (progress != null) {
                progress.accept(new SemanticSearchEngine.IndexProgress(end, pending.size(),
                        batch.get(batch.size() - 1).filePath(), "向量化"));
            }
        }
        return embedded;
    }

    private boolean reuseCompatible(IndexSnapshot previous) {
        if (previous == null || previous.manifest() == null || previous.documentEntries().isEmpty()) return false;
        String expectedSignature = embedder.isConfigured() ? embedder.signature().value() : "";
        if (!Objects.equals(previous.manifest().embeddingModelSignature(), expectedSignature)) return false;
        return embedder.isConfigured()
                ? previous.chunks().stream().allMatch(DocumentChunk::hasEmbedding)
                : previous.chunks().stream().noneMatch(DocumentChunk::hasEmbedding);
    }

    private static List<DocumentChunk> reusableChunks(DocumentIndexEntry entry,
                                                       Map<String, DocumentChunk> chunksById) {
        List<DocumentChunk> result = new ArrayList<>(entry.chunkIds().size());
        for (String chunkId : entry.chunkIds()) {
            DocumentChunk chunk = chunksById.get(chunkId);
            if (chunk == null) return null;
            result.add(chunk);
        }
        return List.copyOf(result);
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("索引构建已取消");
    }

    private static String message(Throwable failure) {
        String value = failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getSimpleName() : value.strip();
    }

    private static long millis(long from, long to) { return (to - from) / 1_000_000L; }

    private record FileWork(DocumentIndexEntry entry, List<DocumentChunk> chunks, boolean requiresEmbedding) { }

    public record BuildResult(List<String> roots, List<DocumentChunk> chunks,
                              List<DocumentIndexEntry> documentEntries,
                              SemanticSearchEngine.IndexReport report,
                              IncrementalIndexPlan plan) {
        public BuildResult {
            roots = List.copyOf(roots);
            chunks = List.copyOf(chunks);
            documentEntries = List.copyOf(documentEntries);
        }
    }
}
