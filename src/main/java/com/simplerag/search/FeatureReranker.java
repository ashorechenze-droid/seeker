package com.simplerag.search;

import java.util.Comparator;
import java.util.List;

/**
 * Local deterministic reranker combining exact/declaration features with normalized first-stage
 * scores. It keeps the pipeline offline and makes the reranker contract ready for a cross-encoder.
 */
public final class FeatureReranker implements SecondStageReranker {
    @Override
    public List<RetrievalCandidate> rerank(QueryAnalyzer.AnalyzedQuery query,
                                           List<RetrievalCandidate> candidates,
                                           int limit) {
        List<RetrievalCandidate> ranked = candidates.stream().map(candidate -> {
            double score;
            if (query.metadataFocused()) {
                score = candidate.fusionScore() * 0.35
                        + candidate.bm25Score() * 0.20
                        + candidate.denseScore() * 0.10
                        + candidate.featureScore() * 0.35;
            } else {
                score = candidate.fusionScore() * 0.40
                        + candidate.bm25Score() * 0.20
                        + candidate.denseScore() * 0.25
                        + candidate.featureScore() * 0.15;
            }
            return candidate.withFinalScore(clamp(score));
        }).sorted(Comparator.comparingDouble(RetrievalCandidate::finalScore).reversed()
                .thenComparing(candidate -> candidate.document().chunk().fileName())
                .thenComparing(candidate -> candidate.document().chunk().sourceLocation()))
                .toList();
        return ranked.size() <= limit ? ranked : List.copyOf(ranked.subList(0, limit));
    }

    @Override
    public String name() {
        return "local-feature-reranker-v1";
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }
}
