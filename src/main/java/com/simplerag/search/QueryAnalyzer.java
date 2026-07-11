package com.simplerag.search;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Converts query text into the exact lexical representation consumed by scorers. */
public final class QueryAnalyzer {
    private final LexicalFeatureExtractor features;

    public QueryAnalyzer(LexicalFeatureExtractor features) { this.features = features; }

    public AnalyzedQuery analyze(String query, Map<String, Integer> documentFrequency, int documents) {
        String cleaned = query == null ? "" : query.strip();
        Map<String, Double> tokens = features.weightedTokens(cleaned, true);
        Map<String, Double> vector = features.tfIdf(tokens, documentFrequency, documents);
        Set<String> concepts = tokens.keySet().stream().filter(token -> token.startsWith("concept:"))
                .collect(Collectors.toUnmodifiableSet());
        return new AnalyzedQuery(cleaned, features.normalize(cleaned), tokens, vector,
                features.norm(vector), concepts);
    }

    public record AnalyzedQuery(String text, String normalizedText, Map<String, Double> tokens,
                                Map<String, Double> vector, double vectorNorm, Set<String> concepts) {
        public boolean empty() { return text.isEmpty(); }
    }
}
