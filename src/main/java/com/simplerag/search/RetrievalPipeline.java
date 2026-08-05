package com.simplerag.search;

import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** BM25/dense candidate generation, reciprocal-rank fusion and second-stage reranking. */
final class RetrievalPipeline {
    private static final int CANDIDATES_PER_BRANCH = 50;
    private static final int RRF_K = 60;

    private final SemanticScorer semanticScorer;
    private final LexicalScorer lexicalScorer;
    private final SecondStageReranker reranker;

    RetrievalPipeline(SemanticScorer semanticScorer, LexicalScorer lexicalScorer,
                      SecondStageReranker reranker) {
        this.semanticScorer = semanticScorer;
        this.lexicalScorer = lexicalScorer;
        this.reranker = reranker;
    }

    List<SearchResult> search(QueryAnalyzer.AnalyzedQuery query,
                              List<RetrievalDocument> documents,
                              Map<String, Integer> documentFrequency,
                              float[] queryEmbedding,
                              int limit,
                              String extensionFilter,
                              RetrievalStrategy strategy) {
        if (limit <= 0 || documents.isEmpty()) return List.of();
        int branchLimit = Math.max(limit * 4, CANDIDATES_PER_BRANCH);
        List<Bm25Index.ScoredDocument> sparse = strategy.usesBm25()
                ? new Bm25Index(documents, documentFrequency).search(query, branchLimit, extensionFilter)
                : List.of();
        List<ScoredDense> dense = strategy.usesDense() && queryEmbedding != null
                ? dense(documents, queryEmbedding, branchLimit, extensionFilter) : List.of();

        if (strategy == RetrievalStrategy.BM25) {
            return directSparse(sparse, query, limit);
        }
        if (strategy == RetrievalStrategy.DENSE) {
            return directDense(dense, limit);
        }

        Map<String, MutableCandidate> fused = new LinkedHashMap<>();
        for (int rank = 0; rank < sparse.size(); rank++) {
            Bm25Index.ScoredDocument hit = sparse.get(rank);
            MutableCandidate candidate = fused.computeIfAbsent(hit.document().chunk().id(),
                    ignored -> new MutableCandidate(hit.document()));
            candidate.bm25 = hit.score();
            candidate.rrf += 1.0 / (RRF_K + rank + 1.0);
        }
        for (int rank = 0; rank < dense.size(); rank++) {
            ScoredDense hit = dense.get(rank);
            MutableCandidate candidate = fused.computeIfAbsent(hit.document().chunk().id(),
                    ignored -> new MutableCandidate(hit.document()));
            candidate.dense = hit.score();
            candidate.rrf += 1.0 / (RRF_K + rank + 1.0);
        }
        if (fused.isEmpty()) return List.of();

        double maxBm25 = fused.values().stream().mapToDouble(item -> item.bm25).max().orElse(1);
        double maxRrf = fused.values().stream().mapToDouble(item -> item.rrf).max().orElse(1);
        List<RetrievalCandidate> candidates = new ArrayList<>(fused.size());
        for (MutableCandidate item : fused.values()) {
            LexicalScorer.Score features = lexicalScorer.score(query, item.document.chunk(),
                    item.document.tokens(), item.document.tfIdfVector(), item.document.tfIdfNorm());
            candidates.add(new RetrievalCandidate(item.document,
                    normalize(item.bm25, maxBm25), clamp(item.dense), normalize(item.rrf, maxRrf),
                    saturate(features.value()), normalize(item.rrf, maxRrf)));
        }
        candidates.sort(Comparator.comparingDouble(RetrievalCandidate::fusionScore).reversed()
                .thenComparing(candidate -> candidate.document().chunk().fileName()));
        int rerankPool = Math.min(candidates.size(), Math.max(limit * 4, 20));
        candidates = List.copyOf(candidates.subList(0, rerankPool));
        List<RetrievalCandidate> ranked = strategy.usesReranker()
                ? reranker.rerank(query, candidates, rerankPool)
                : candidates;
        return toResults(ranked, strategy, limit);
    }

    private List<ScoredDense> dense(List<RetrievalDocument> documents, float[] queryEmbedding,
                                    int limit, String extensionFilter) {
        List<ScoredDense> results = new ArrayList<>();
        for (RetrievalDocument document : documents) {
            DocumentChunk chunk = document.chunk();
            if (!matchesExtension(chunk, extensionFilter) || !chunk.hasEmbedding()) continue;
            double score = Math.max(0, semanticScorer.score(queryEmbedding, chunk.embedding()));
            if (score >= 0.15) results.add(new ScoredDense(document, score));
        }
        results.sort(Comparator.comparingDouble(ScoredDense::score).reversed()
                .thenComparing(hit -> hit.document().chunk().fileName())
                .thenComparing(hit -> hit.document().chunk().sourceLocation()));
        return results.size() <= limit ? results : List.copyOf(results.subList(0, limit));
    }

    private List<SearchResult> directSparse(List<Bm25Index.ScoredDocument> ranked,
                                            QueryAnalyzer.AnalyzedQuery query, int limit) {
        double max = ranked.stream().mapToDouble(Bm25Index.ScoredDocument::score).max().orElse(1);
        return ranked.stream().limit(limit).map(hit -> {
            LexicalScorer.Score features = lexicalScorer.score(query, hit.document().chunk(),
                    hit.document().tokens(), hit.document().tfIdfVector(), hit.document().tfIdfNorm());
            String reason = features.declarationMatch() ? "BM25 代码定义匹配"
                    : features.metadataMatch() ? "BM25 文件元数据匹配" : "BM25 词法召回";
            return new SearchResult(hit.document().chunk(), normalize(hit.score(), max), reason);
        }).toList();
    }

    private static List<SearchResult> directDense(List<ScoredDense> ranked, int limit) {
        return ranked.stream().limit(limit)
                .map(hit -> new SearchResult(hit.document().chunk(), clamp(hit.score()), "向量语义召回"))
                .toList();
    }

    private List<SearchResult> toResults(List<RetrievalCandidate> ranked, RetrievalStrategy strategy, int limit) {
        Map<String, Integer> seenDocuments = new HashMap<>();
        List<SearchResult> results = new ArrayList<>();
        for (RetrievalCandidate candidate : ranked) {
            DocumentChunk chunk = candidate.document().chunk();
            // Search UI may still show several passages, but pathological single-file domination is capped.
            int count = seenDocuments.getOrDefault(chunk.path(), 0);
            if (count >= 3) continue;
            seenDocuments.put(chunk.path(), count + 1);
            String reason = strategy.usesReranker()
                    ? "BM25 + 向量 RRF 融合 · " + reranker.name()
                    : "BM25 + 向量 RRF 融合";
            double score = strategy.usesReranker() ? candidate.finalScore() : candidate.fusionScore();
            results.add(new SearchResult(chunk, clamp(score), reason));
            if (results.size() >= limit) break;
        }
        return List.copyOf(results);
    }

    private static boolean matchesExtension(DocumentChunk chunk, String filter) {
        return filter == null || filter.isBlank() || "全部".equals(filter)
                || "all".equalsIgnoreCase(filter) || "*".equals(filter)
                || chunk.extension().equalsIgnoreCase(filter);
    }

    private static double normalize(double value, double maximum) {
        return maximum <= 0 ? 0 : clamp(value / maximum);
    }

    private static double saturate(double value) {
        return value <= 0 ? 0 : clamp(1.0 - Math.exp(-value));
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record ScoredDense(RetrievalDocument document, double score) { }

    private static final class MutableCandidate {
        private final RetrievalDocument document;
        private double bm25;
        private double dense;
        private double rrf;

        private MutableCandidate(RetrievalDocument document) {
            this.document = document;
        }
    }
}
