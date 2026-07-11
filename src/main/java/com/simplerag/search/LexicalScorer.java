package com.simplerag.search;

import com.simplerag.model.DocumentChunk;

import java.util.Map;

/** Existing TF-IDF, exact-text, filename and concept scoring policy. */
public final class LexicalScorer {
    private final LexicalFeatureExtractor features;

    public LexicalScorer(LexicalFeatureExtractor features) { this.features = features; }

    public Score score(QueryAnalyzer.AnalyzedQuery query, DocumentChunk chunk,
                       Map<String, Double> documentTokens, Map<String, Double> documentVector,
                       double documentNorm) {
        double cosine = cosine(query.vector(), query.vectorNorm(), documentVector, documentNorm);
        String normalizedContent = features.normalize(chunk.content());
        String normalizedName = features.normalize(chunk.fileName());
        double exactBoost = normalizedContent.contains(query.normalizedText()) ? 0.24 : 0.0;
        double nameBoost = query.tokens().keySet().stream()
                .filter(token -> !token.startsWith("concept:") && normalizedName.contains(token))
                .count() * 0.035;
        long conceptMatches = query.concepts().stream().filter(documentTokens::containsKey).count();
        double conceptBoost = Math.min(0.30, conceptMatches * 0.10);
        return new Score(cosine * 0.74 + exactBoost + nameBoost + conceptBoost,
                conceptMatches, exactBoost > 0);
    }

    private static double cosine(Map<String, Double> left, double leftNorm,
                                 Map<String, Double> right, double rightNorm) {
        if (leftNorm == 0 || rightNorm == 0) return 0;
        Map<String, Double> smaller = left.size() <= right.size() ? left : right;
        Map<String, Double> larger = left.size() <= right.size() ? right : left;
        double dot = 0;
        for (Map.Entry<String, Double> entry : smaller.entrySet()) {
            dot += entry.getValue() * larger.getOrDefault(entry.getKey(), 0.0);
        }
        return dot / (leftNorm * rightNorm);
    }

    public record Score(double value, long conceptMatches, boolean exactMatch) { }
}
