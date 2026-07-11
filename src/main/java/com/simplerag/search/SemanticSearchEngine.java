package com.simplerag.search;

import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SearchResult;
import com.simplerag.model.SemanticHighlight;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class SemanticSearchEngine {
    private final TextEmbedder embeddingProvider;
    private final DocumentScanner documentScanner;
    private final DocumentReaderRegistry readerRegistry;
    private final ChunkerRegistry chunkerRegistry;
    private final LexicalFeatureExtractor lexicalFeatures;
    private final QueryAnalyzer queryAnalyzer;
    private final LexicalScorer lexicalScorer;
    private final SemanticHighlightService highlightService;
    private final SemanticScorer semanticScorer;
    private final RankingPolicy rankingPolicy;
    private volatile State state = State.empty();
    private volatile List<String> roots = List.of();
    private volatile long indexedAt;
    private volatile boolean embeddingsActive;
    private volatile boolean semanticCompatible;
    private volatile IndexManifest manifest;
    private String cachedQueryText = "";
    private float[] cachedQueryEmbedding;

    public SemanticSearchEngine(TextEmbedder embeddingProvider) {
        this(embeddingProvider, new DocumentScanner(), new DocumentReaderRegistry(), new ChunkerRegistry(),
                new LexicalFeatureExtractor(), new SemanticScorer(), RankingPolicy.defaultPolicy());
    }

    public SemanticSearchEngine(TextEmbedder embeddingProvider, SemanticScorer semanticScorer,
                                RankingPolicy rankingPolicy) {
        this(embeddingProvider, new DocumentScanner(), new DocumentReaderRegistry(), new ChunkerRegistry(),
                new LexicalFeatureExtractor(), semanticScorer, rankingPolicy);
    }

    public SemanticSearchEngine(TextEmbedder embeddingProvider, DocumentScanner documentScanner,
                                DocumentReaderRegistry readerRegistry, ChunkerRegistry chunkerRegistry,
                                LexicalFeatureExtractor lexicalFeatures, SemanticScorer semanticScorer,
                                RankingPolicy rankingPolicy) {
        this.embeddingProvider = embeddingProvider;
        this.documentScanner = documentScanner;
        this.readerRegistry = readerRegistry;
        this.chunkerRegistry = chunkerRegistry;
        this.lexicalFeatures = lexicalFeatures;
        this.queryAnalyzer = new QueryAnalyzer(lexicalFeatures);
        this.lexicalScorer = new LexicalScorer(lexicalFeatures);
        this.highlightService = new SemanticHighlightService(embeddingProvider, semanticScorer);
        this.semanticScorer = semanticScorer;
        this.rankingPolicy = rankingPolicy;
    }

    public IndexReport index(List<Path> sourceRoots, Consumer<IndexProgress> progress) throws IOException {
        DocumentScanner.ScanResult scan = documentScanner.scan(sourceRoots);
        List<DocumentScanner.ScannedDocument> files = scan.documents();

        List<DocumentChunk> chunks = new ArrayList<>();
        int processed = 0;
        int skipped = 0;
        for (DocumentScanner.ScannedDocument document : files) {
            checkInterrupted();
            Path file = document.path();
            try {
                DocumentReaderRegistry.ReadDocument read = readerRegistry.read(file, document.root());
                if (read != null) chunks.addAll(chunkerRegistry.chunk(read));
            } catch (IOException | RuntimeException unreadable) {
                skipped++;
            }
            processed++;
            if (progress != null && (processed == files.size() || processed % 10 == 0)) {
                progress.accept(new IndexProgress(processed, files.size(), file, "扫描"));
            }
        }

        embeddingsActive = false;
        if (embeddingProvider.isConfigured() && !chunks.isEmpty()) {
            List<DocumentChunk> embeddedChunks = new ArrayList<>(chunks.size());
            final int batchSize = 24;
            for (int start = 0; start < chunks.size(); start += batchSize) {
                checkInterrupted();
                int end = Math.min(chunks.size(), start + batchSize);
                List<DocumentChunk> batch = chunks.subList(start, end);
                List<String> texts = batch.stream()
                        .map(chunk -> chunk.fileName() + "\n" + chunk.content()).toList();
                List<float[]> vectors;
                try {
                    vectors = embeddingProvider.embed(texts);
                } catch (IOException failure) {
                    throw new IOException("生成本地语义向量失败：" + failure.getMessage(), failure);
                }
                for (int i = 0; i < batch.size(); i++) {
                    embeddedChunks.add(batch.get(i).withEmbedding(vectors.get(i)));
                }
                if (progress != null) {
                    progress.accept(new IndexProgress(end, chunks.size(), batch.get(batch.size() - 1).filePath(), "向量化"));
                }
            }
            chunks = embeddedChunks;
            embeddingsActive = true;
            semanticCompatible = true;
        }

        clearHighlightCache();
        this.state = State.build(chunks, lexicalFeatures);
        this.roots = scan.roots().stream().map(Path::toString).toList();
        this.indexedAt = System.currentTimeMillis();
        return new IndexReport(files.size() - skipped, chunks.size(), skipped);
    }

    public List<SearchResult> search(String query, int limit, String extensionFilter) {
        State current = state;
        QueryAnalyzer.AnalyzedQuery analyzed = queryAnalyzer.analyze(query, current.documentFrequency, current.chunks.size());
        String cleaned = analyzed.text();
        if (analyzed.empty()) {
            return List.of();
        }
        Map<String, Double> queryTokens = analyzed.tokens();
        if (queryTokens.isEmpty() && !semanticCompatible) {
            return List.of();
        }
        float[] semanticQuery = null;
        if (semanticCompatible) {
            try {
                semanticQuery = semanticQuery(cleaned);
                if (!semanticScorer.queryCompatible(semanticQuery,
                        current.chunks.stream().map(item -> item.chunk).toList())) semanticQuery = null;
                embeddingsActive = semanticQuery != null;
            } catch (IOException ignored) {
                embeddingsActive = false;
            }
        }

        List<SearchResult> results = new ArrayList<>();
        for (IndexedChunk indexed : current.chunks) {
            DocumentChunk chunk = indexed.chunk;
            if (extensionFilter != null && !extensionFilter.isBlank() && !"全部".equals(extensionFilter)
                    && !chunk.extension().equalsIgnoreCase(extensionFilter)) {
                continue;
            }
            LexicalScorer.Score lexical = lexicalScorer.score(analyzed, chunk,
                    indexed.tokens, indexed.vector, indexed.norm);
            double lexicalScore = lexical.value();
            double semanticScore = semanticQuery != null && chunk.hasEmbedding()
                    ? Math.max(0.0, semanticScorer.score(semanticQuery, chunk.embedding())) : 0.0;
            double score = rankingPolicy.combine(semanticScore, lexicalScore, semanticQuery != null);
            if (lexicalScore >= rankingPolicy.lexicalResultThreshold()
                    || semanticScore >= rankingPolicy.semanticResultThreshold()) {
                String reason = semanticScore >= rankingPolicy.semanticResultThreshold() && semanticScore >= lexicalScore
                        ? "向量语义匹配" : lexical.conceptMatches() > 0 ? "语义概念匹配"
                        : lexical.exactMatch() ? "原文匹配" : "内容相似";
                results.add(new SearchResult(chunk, Math.min(1.0, score), reason));
            }
        }
        results.sort(Comparator.comparingDouble(SearchResult::score).reversed()
                .thenComparing(result -> result.chunk().fileName()));
        return results.size() <= limit ? results : List.copyOf(results.subList(0, limit));
    }

    public List<SemanticHighlight> semanticHighlights(String query, DocumentChunk chunk, int limit)
            throws IOException {
        return highlightService.locate(query, chunk, limit, semanticCompatible);
    }

    public void restore(IndexSnapshot snapshot) {
        clearHighlightCache();
        this.state = State.build(snapshot.chunks(), lexicalFeatures);
        this.roots = List.copyOf(snapshot.roots());
        this.indexedAt = snapshot.indexedAt();
        this.manifest = snapshot.manifest();
        this.semanticCompatible = isSemanticCompatible(snapshot);
        this.embeddingsActive = semanticCompatible;
    }

    public IndexSnapshot snapshot() {
        List<DocumentChunk> chunks = state.chunks.stream().map(indexed -> indexed.chunk).toList();
        return new IndexSnapshot(IndexSnapshot.CURRENT_VERSION, roots, chunks, indexedAt,
                state.hasEmbeddings ? embeddingProvider.modelName() : "", manifest);
    }

    public IndexSnapshot snapshot(IndexManifest value) {
        this.manifest = value;
        return snapshot();
    }

    public List<String> roots() {
        return roots;
    }

    public int chunkCount() {
        return state.chunks.size();
    }

    public int fileCount() {
        return (int) state.chunks.stream().map(indexed -> indexed.chunk.path()).distinct().count();
    }

    public Set<String> extensions() {
        return state.chunks.stream().map(indexed -> indexed.chunk.extension())
                .filter(value -> !value.isBlank()).collect(Collectors.toCollection(java.util.TreeSet::new));
    }

    public boolean semanticEnabled() {
        return semanticCompatible && embeddingsActive && state.hasEmbeddings;
    }

    public void markStale() {
        semanticCompatible = false;
        embeddingsActive = false;
        clearHighlightCache();
    }

    public boolean semanticModelConfigured() {
        return embeddingProvider.isConfigured();
    }

    public String semanticStatus() {
        if (!embeddingProvider.isConfigured()) return "未安装语义模型";
        if (!state.hasEmbeddings) return "模型已安装，需重建索引";
        if (!semanticCompatible) return "索引向量与当前模型不兼容，需重建";
        return embeddingProvider.status();
    }

    private boolean isSemanticCompatible(IndexSnapshot snapshot) {
        return state.hasEmbeddings && semanticScorer.compatible(embeddingProvider, snapshot.manifest(),
                state.chunks.stream().map(item -> item.chunk).toList());
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("索引构建已取消");
        }
    }

    private synchronized void clearHighlightCache() {
        highlightService.clear();
    }

    private synchronized float[] semanticQuery(String query) throws IOException {
        if (query.equals(cachedQueryText) && cachedQueryEmbedding != null) {
            return cachedQueryEmbedding;
        }
        float[] embedding = embeddingProvider.embed(List.of(query)).get(0);
        cachedQueryEmbedding = embedding;
        cachedQueryText = query;
        return embedding;
    }

    public record IndexProgress(int processed, int total, Path currentFile, String stage) {
    }

    public record IndexReport(int files, int chunks, int skipped) {
    }

    private record IndexedChunk(DocumentChunk chunk, Map<String, Double> tokens,
                                Map<String, Double> vector, double norm) {
    }

    private record State(List<IndexedChunk> chunks, Map<String, Integer> documentFrequency,
                         boolean hasEmbeddings) {
        static State empty() {
            return new State(List.of(), Map.of(), false);
        }

        static State build(List<DocumentChunk> source, LexicalFeatureExtractor features) {
            List<Map<String, Double>> tokenMaps = source.stream()
                    .map(chunk -> features.weightedTokens(chunk.fileName() + "\n" + chunk.content(), false)).toList();
            Map<String, Integer> df = new HashMap<>();
            tokenMaps.forEach(tokens -> new HashSet<>(tokens.keySet()).forEach(token -> df.merge(token, 1, Integer::sum)));
            List<IndexedChunk> indexed = new ArrayList<>(source.size());
            for (int i = 0; i < source.size(); i++) {
                Map<String, Double> tokens = tokenMaps.get(i);
                Map<String, Double> vector = features.tfIdf(tokens, df, source.size());
                indexed.add(new IndexedChunk(source.get(i), tokens, vector, features.norm(vector)));
            }
            boolean hasEmbeddings = source.stream().anyMatch(DocumentChunk::hasEmbedding);
            return new State(List.copyOf(indexed), Map.copyOf(df), hasEmbeddings);
        }
    }
}
