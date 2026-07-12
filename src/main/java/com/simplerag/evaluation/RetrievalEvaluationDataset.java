package com.simplerag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public record RetrievalEvaluationDataset(String name, int version, double minimumRecallAt5,
                                         double minimumMrrAt10, double minimumNdcgAt10,
                                         List<RetrievalEvaluationCase> cases) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public RetrievalEvaluationDataset {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name 不能为空");
        if (version < 1) throw new IllegalArgumentException("version 必须大于 0");
        validateThreshold(minimumRecallAt5, "minimumRecallAt5");
        validateThreshold(minimumMrrAt10, "minimumMrrAt10");
        validateThreshold(minimumNdcgAt10, "minimumNdcgAt10");
        cases = List.copyOf(cases == null ? List.of() : cases);
        if (cases.isEmpty()) throw new IllegalArgumentException("cases 不能为空");
    }

    private static void validateThreshold(double value, String field) {
        if (value < 0 || value > 1) throw new IllegalArgumentException(field + " 必须在 0..1 之间");
    }

    public static RetrievalEvaluationDataset load(Path path) throws IOException {
        return JSON.readValue(path.toFile(), RetrievalEvaluationDataset.class);
    }
}
