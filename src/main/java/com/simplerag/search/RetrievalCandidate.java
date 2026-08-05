package com.simplerag.search;

/** Candidate with independently observable scores for every retrieval stage. */
public record RetrievalCandidate(
        RetrievalDocument document,
        double bm25Score,
        double denseScore,
        double fusionScore,
        double featureScore,
        double finalScore
) {
    public RetrievalCandidate withFinalScore(double score) {
        return new RetrievalCandidate(document, bm25Score, denseScore, fusionScore, featureScore, score);
    }
}
