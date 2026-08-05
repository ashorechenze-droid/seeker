package com.simplerag.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Exact BM25 scorer used as an independent sparse retrieval branch. */
final class Bm25Index {
    private static final double K1 = 1.5;
    private static final double B = 0.75;

    private final List<RetrievalDocument> documents;
    private final Map<String, Integer> documentFrequency;
    private final double averageLength;

    Bm25Index(List<RetrievalDocument> documents, Map<String, Integer> documentFrequency) {
        this.documents = List.copyOf(documents);
        this.documentFrequency = Map.copyOf(documentFrequency);
        this.averageLength = Math.max(1.0, documents.stream()
                .mapToDouble(RetrievalDocument::length).average().orElse(1.0));
    }

    List<ScoredDocument> search(QueryAnalyzer.AnalyzedQuery query, int limit, String extensionFilter) {
        List<ScoredDocument> scored = new ArrayList<>();
        for (RetrievalDocument document : documents) {
            if (!matchesExtension(document, extensionFilter)) continue;
            double score = score(query.tokens(), document);
            if (score > 0) scored.add(new ScoredDocument(document, score));
        }
        scored.sort(Comparator.comparingDouble(ScoredDocument::score).reversed()
                .thenComparing(item -> item.document().chunk().fileName())
                .thenComparing(item -> item.document().chunk().sourceLocation()));
        return scored.size() <= limit ? scored : List.copyOf(scored.subList(0, limit));
    }

    private double score(Map<String, Double> queryTokens, RetrievalDocument document) {
        double score = 0;
        for (Map.Entry<String, Double> queryTerm : queryTokens.entrySet()) {
            double frequency = document.tokens().getOrDefault(queryTerm.getKey(), 0.0);
            if (frequency <= 0) continue;
            int df = documentFrequency.getOrDefault(queryTerm.getKey(), 0);
            double idf = Math.log(1.0 + (documents.size() - df + 0.5) / (df + 0.5));
            double denominator = frequency + K1 * (1.0 - B + B * document.length() / averageLength);
            double queryWeight = Math.min(3.0, Math.max(0.25, queryTerm.getValue()));
            score += idf * frequency * (K1 + 1.0) / denominator * queryWeight;
        }
        return score;
    }

    private static boolean matchesExtension(RetrievalDocument document, String extensionFilter) {
        return extensionFilter == null || extensionFilter.isBlank() || "全部".equals(extensionFilter)
                || "all".equalsIgnoreCase(extensionFilter) || "*".equals(extensionFilter)
                || document.chunk().extension().equalsIgnoreCase(extensionFilter);
    }

    record ScoredDocument(RetrievalDocument document, double score) { }
}
