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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Builds a complete new snapshot while reusing unchanged chunks and embeddings from a published snapshot. */
public final class IncrementalIndexBuilder {
    private static final int EMBEDDING_BATCH_SIZE = 24;

    /** Overrides the CPU-derived reader concurrency; 1 restores fully sequential reading. */
    public static final String READ_THREADS_PROPERTY = "simplerag.index.readThreads";
    private static final int MAX_READ_THREADS = 32;
    private static final int DEFAULT_MAX_READ_THREADS = 8;

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

        List<DocumentIndexEntry> previousEntries = previous == null ? List.of() : previous.documentEntries();
        Map<String, DocumentIndexEntry> previousByKey = new HashMap<>();
        previousEntries.forEach(entry -> previousByKey.put(entry.key(), entry));

        List<FileFingerprint> fingerprints = new ArrayList<>();
        List<IndexableDocument> currentDocuments = new ArrayList<>();
        Map<String, DocumentScanner.ScannedDocument> documentsByKey = new LinkedHashMap<>();
        List<IndexBuildWarning> warnings = new ArrayList<>(scan.warnings());
        int skipped = (int) scan.warnings().stream().filter(IndexBuildWarning::skipped).count();
        for (DocumentScanner.ScannedDocument document : scan.documents()) {
            checkInterrupted();
            try {
                FileFingerprint fingerprint = FileFingerprint.capture(document,
                        previousByKey.get(FileFingerprint.key(document)));
                fingerprints.add(fingerprint);
                currentDocuments.add(new IndexableDocument(fingerprint, document.readerId(), document.readerVersion()));
                documentsByKey.put(fingerprint.key(), document);
            } catch (IOException | RuntimeException unreadable) {
                skipped++;
                warnings.add(new IndexBuildWarning(document.path(), document.readerId(),
                        "无法读取文件元数据：" + message(unreadable), true));
            }
        }

        long fingerprintedAt = System.nanoTime();
        boolean reuseCompatible = reuseCompatible(previous);
        IncrementalIndexPlan plan = planner.plan(previousEntries, currentDocuments,
                ChunkerRegistry.CHUNKING_VERSION, reuseCompatible);
        Map<String, DocumentIndexEntry> reusableEntries = new HashMap<>();
        plan.reused().forEach(entry -> reusableEntries.put(entry.key(), entry));
        Map<String, DocumentChunk> previousChunks = new HashMap<>();
        if (previous != null) previous.chunks().forEach(chunk -> previousChunks.put(chunk.id(), chunk));

        ReadStage stage = readAndChunk(fingerprints, documentsByKey, reusableEntries, previousChunks, progress);
        List<FileWork> files = stage.files();
        warnings.addAll(stage.warnings());
        skipped += stage.skipped();

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
                "fingerprint", millis(scannedAt, fingerprintedAt),
                "readAndChunk", millis(fingerprintedAt, readAt),
                "embedding", millis(readAt, embeddedAt),
                "assemble", millis(embeddedAt, completedAt),
                "total", millis(started, completedAt));
        return new BuildResult(scan.roots().stream().map(Path::toString).toList(), completeChunks,
                completeEntries, new SemanticSearchEngine.IndexReport(files.size(), completeChunks.size(), skipped,
                warnings, timings), plan);
    }

    /**
     * Reads and chunks every file the plan could not reuse, in parallel when there is more than one.
     *
     * <p>Results are collected strictly by original scan position, so chunk order, chunk identity and
     * warning order stay byte-for-byte identical to sequential reading. Readers hold no cross-call
     * state ({@code PDDocument} / {@code OPCPackage} are created per read) and {@link ChunkerRegistry}
     * is stateless, so only the collection order needs to be pinned down.
     */
    private ReadStage readAndChunk(List<FileFingerprint> fingerprints,
                                   Map<String, DocumentScanner.ScannedDocument> documentsByKey,
                                   Map<String, DocumentIndexEntry> reusableEntries,
                                   Map<String, DocumentChunk> previousChunks,
                                   Consumer<SemanticSearchEngine.IndexProgress> progress) throws IOException {
        int total = fingerprints.size();
        FileWork[] reusedWork = new FileWork[total];
        List<Integer> pending = new ArrayList<>();
        for (int index = 0; index < total; index++) {
            DocumentIndexEntry reusable = reusableEntries.get(fingerprints.get(index).key());
            List<DocumentChunk> chunks = reusable == null ? null : reusableChunks(reusable, previousChunks);
            if (chunks == null) pending.add(index);
            else reusedWork[index] = new FileWork(reusable, chunks, false);
        }

        int threads = readThreads();
        ExecutorService executor = pending.size() > 1 && threads > 1
                ? newReadExecutor(Math.min(threads, pending.size())) : null;
        try {
            Map<Integer, Future<ReadOutcome>> futures = new HashMap<>();
            if (executor != null) {
                for (int index : pending) {
                    FileFingerprint fingerprint = fingerprints.get(index);
                    DocumentScanner.ScannedDocument scanned = documentsByKey.get(fingerprint.key());
                    futures.put(index, executor.submit(() -> readAndChunkOne(scanned, fingerprint)));
                }
            }

            List<FileWork> files = new ArrayList<>(total);
            List<IndexBuildWarning> warnings = new ArrayList<>();
            int skipped = 0;
            for (int index = 0; index < total; index++) {
                checkInterrupted();
                FileFingerprint fingerprint = fingerprints.get(index);
                DocumentScanner.ScannedDocument scanned = documentsByKey.get(fingerprint.key());
                if (reusedWork[index] != null) {
                    files.add(reusedWork[index]);
                } else {
                    ReadOutcome outcome = executor == null
                            ? readAndChunkOne(scanned, fingerprint)
                            : await(futures.get(index), executor);
                    if (outcome.buildCancelled()) throw new InterruptedIOException("索引构建已取消");
                    if (outcome.work() != null) files.add(outcome.work());
                    warnings.addAll(outcome.warnings());
                    if (outcome.readFailed()) skipped++;
                }
                int processed = index + 1;
                if (progress != null && (processed == total || processed % 10 == 0)) {
                    progress.accept(new SemanticSearchEngine.IndexProgress(processed, total,
                            scanned.path(), "扫描"));
                }
            }
            return new ReadStage(files, warnings, skipped);
        } finally {
            if (executor != null) executor.shutdownNow();
        }
    }

    private ReadOutcome readAndChunkOne(DocumentScanner.ScannedDocument scanned, FileFingerprint fingerprint) {
        if (Thread.currentThread().isInterrupted()) return ReadOutcome.cancelled();
        try {
            ReadDocument read = readers.read(scanned.path(), scanned.root());
            List<DocumentChunk> chunks = read == null ? List.of() : chunkers.chunk(read);
            IndexableDocument current = new IndexableDocument(fingerprint,
                    scanned.readerId(), scanned.readerVersion());
            DocumentIndexEntry entry = DocumentIndexEntry.from(current, ChunkerRegistry.CHUNKING_VERSION,
                    chunks.stream().map(DocumentChunk::id).toList());
            List<IndexBuildWarning> readWarnings = read == null ? List.of()
                    : read.warnings().stream()
                    .map(warning -> new IndexBuildWarning(scanned.path(), scanned.readerId(), warning, false))
                    .toList();
            return ReadOutcome.read(new FileWork(entry, chunks, true), readWarnings);
        } catch (IOException | RuntimeException unreadable) {
            return ReadOutcome.failed(new IndexBuildWarning(scanned.path(), scanned.readerId(),
                    message(unreadable), true));
        }
    }

    private static ReadOutcome await(Future<ReadOutcome> future, ExecutorService executor) throws IOException {
        try {
            return future.get();
        } catch (InterruptedException cancelled) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("索引构建已取消");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException alreadyTyped) throw alreadyTyped;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException("读取文档失败：" + message(cause), cause);
        }
    }

    private static ExecutorService newReadExecutor(int threads) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "simplerag-index-read-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(threads, factory);
    }

    private static int readThreads() {
        try {
            int configured = Integer.parseInt(System.getProperty(READ_THREADS_PROPERTY, "").strip());
            if (configured > 0) return Math.min(configured, MAX_READ_THREADS);
        } catch (NumberFormatException absentOrInvalid) {
            // Fall through to the CPU-derived default.
        }
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors() - 1, DEFAULT_MAX_READ_THREADS));
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
                        .map(chunk -> chunk.fileName() + "\n" + chunk.sourceLocation() + "\n" + chunk.content()).toList());
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
        if (failure == null) return "未知错误";
        String value = failure.getMessage();
        return value == null || value.isBlank() ? failure.getClass().getSimpleName() : value.strip();
    }

    private static long millis(long from, long to) { return (to - from) / 1_000_000L; }

    private record FileWork(DocumentIndexEntry entry, List<DocumentChunk> chunks, boolean requiresEmbedding) { }

    private record ReadStage(List<FileWork> files, List<IndexBuildWarning> warnings, int skipped) { }

    private record ReadOutcome(FileWork work, List<IndexBuildWarning> warnings,
                               boolean readFailed, boolean buildCancelled) {
        static ReadOutcome read(FileWork work, List<IndexBuildWarning> warnings) {
            return new ReadOutcome(work, warnings, false, false);
        }

        static ReadOutcome failed(IndexBuildWarning warning) {
            return new ReadOutcome(null, List.of(warning), true, false);
        }

        static ReadOutcome cancelled() {
            return new ReadOutcome(null, List.of(), false, true);
        }
    }

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
