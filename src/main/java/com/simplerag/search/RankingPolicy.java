package com.simplerag.search;

public record RankingPolicy(
        double semanticWeight,
        double lexicalWeight,
        double semanticResultThreshold,
        double lexicalResultThreshold,
        int version
) {
    public static RankingPolicy defaultPolicy() {
        return new RankingPolicy(0.78, 0.22, 0.22, 0.045, 1);
    }

    public double combine(double semanticScore, double lexicalScore, boolean semanticAvailable) {
        return semanticAvailable
                ? semanticScore * semanticWeight + lexicalScore * lexicalWeight
                : lexicalScore;
    }
}
