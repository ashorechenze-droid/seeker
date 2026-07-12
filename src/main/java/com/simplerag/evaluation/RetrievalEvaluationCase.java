package com.simplerag.evaluation;

import java.util.List;

public record RetrievalEvaluationCase(
        String id,
        String query,
        String language,
        List<String> expectedDocuments,
        List<String> expectedPassages,
        List<String> mustNotReturn,
        String category
) {
    public RetrievalEvaluationCase {
        id = required(id, "id");
        query = required(query, "query");
        language = required(language, "language");
        category = required(category, "category");
        expectedDocuments = List.copyOf(expectedDocuments == null ? List.of() : expectedDocuments);
        expectedPassages = List.copyOf(expectedPassages == null ? List.of() : expectedPassages);
        mustNotReturn = List.copyOf(mustNotReturn == null ? List.of() : mustNotReturn);
        if (expectedDocuments.isEmpty()) throw new IllegalArgumentException("expectedDocuments 不能为空");
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return value;
    }
}
