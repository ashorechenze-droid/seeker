package com.simplerag.evaluation;

import java.util.List;

public record RetrievalEvaluationReport(
        String dataset,
        int datasetVersion,
        int rankingPolicyVersion,
        String generatedAt,
        int queryCount,
        double recallAt5,
        double mrrAt10,
        double ndcgAt10,
        double coldQueryLatencyMillis,
        double cachedQueryLatencyMillis,
        double indexingMillis,
        double estimatedMemoryBytesPerThousandChunks,
        List<CaseResult> cases
) {
    public record CaseResult(String id, double recallAt5, double reciprocalRank,
                             double ndcgAt10, boolean forbiddenResultReturned,
                             List<String> returnedDocuments) {
        public CaseResult {
            returnedDocuments = List.copyOf(returnedDocuments);
        }
    }
}
