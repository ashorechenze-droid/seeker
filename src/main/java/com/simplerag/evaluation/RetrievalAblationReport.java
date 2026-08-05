package com.simplerag.evaluation;

import java.util.Map;

/** Side-by-side report generated from the same corpus, model and query set. */
public record RetrievalAblationReport(
        String dataset,
        int datasetVersion,
        String generatedAt,
        Map<String, RetrievalEvaluationReport> strategies
) {
    public RetrievalAblationReport {
        strategies = Map.copyOf(strategies);
    }
}
