package com.simplerag.evaluation;

import java.util.List;
import java.util.Map;

public record RetrievalEvaluationReport(
        String dataset,
        int datasetVersion,
        int rankingPolicyVersion,
        String retrievalStrategy,
        String generatedAt,
        int queryCount,
        double recallAt5,
        double recallAt20,
        double hitRateAt5,
        double precisionAt5,
        double mrrAt10,
        double ndcgAt10,
        double uniqueDocumentRatioAt10,
        double coldQueryLatencyMillis,
        double cachedQueryLatencyMillis,
        double p50QueryLatencyMillis,
        double p95QueryLatencyMillis,
        double indexingMillis,
        double estimatedMemoryBytesPerThousandChunks,
        Map<String, AggregateMetrics> categories,
        List<CaseResult> cases
) {
    public RetrievalEvaluationReport {
        categories = categories == null ? Map.of() : Map.copyOf(categories);
        cases = cases == null ? List.of() : List.copyOf(cases);
    }

    public record AggregateMetrics(int queryCount, double recallAt5, double hitRateAt5,
                                   double mrrAt10, double ndcgAt10) { }

    public record CaseResult(String id, String split, String category,
                             double recallAt5, double recallAt20, double hitRateAt5,
                             double precisionAt5, double reciprocalRank, double ndcgAt10,
                             boolean forbiddenResultReturned, boolean hardNegativeOutrankedPositive,
                             double uniqueDocumentRatioAt10,
                             List<String> returnedDocuments) {
        public CaseResult {
            returnedDocuments = List.copyOf(returnedDocuments);
        }
    }
}
