package com.simplerag.search;

public record RankingPolicy(
        double semanticWeight,
        double lexicalWeight,
        double semanticResultThreshold,
        double lexicalResultThreshold,
        int version
) {
    public static RankingPolicy defaultPolicy() {
        return new RankingPolicy(0.78, 0.22, 0.22, 0.045, 2);
    }

    public double combine(double semanticScore, double lexicalScore, boolean semanticAvailable) {
        return semanticAvailable
                ? semanticScore * semanticWeight + lexicalScore * lexicalWeight
                : lexicalScore;
    }

    public double combine(double semanticScore, double lexicalScore, boolean semanticAvailable,
                          boolean metadataFocused) {
        if (!semanticAvailable) return lexicalScore;
        if (metadataFocused) return semanticScore * 0.58 + lexicalScore * 0.42;
        return combine(semanticScore, lexicalScore, true);
    }
}
