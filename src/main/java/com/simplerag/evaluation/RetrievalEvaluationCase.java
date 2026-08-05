package com.simplerag.evaluation;

import java.util.List;

public record RetrievalEvaluationCase(
        String id,
        String query,
        String language,
        List<String> expectedDocuments,
        List<String> expectedPassages,
        List<String> mustNotReturn,
        String category,
        String split,
        List<String> hardNegatives,
        Boolean answerable
) {
    public RetrievalEvaluationCase {
        id = required(id, "id");
        query = required(query, "query");
        language = required(language, "language");
        category = required(category, "category");
        split = split == null || split.isBlank() ? "test" : split.strip();
        expectedDocuments = List.copyOf(expectedDocuments == null ? List.of() : expectedDocuments);
        expectedPassages = List.copyOf(expectedPassages == null ? List.of() : expectedPassages);
        mustNotReturn = List.copyOf(mustNotReturn == null ? List.of() : mustNotReturn);
        hardNegatives = List.copyOf(hardNegatives == null ? List.of() : hardNegatives);
        answerable = answerable == null ? Boolean.TRUE : answerable;
        if (answerable && expectedDocuments.isEmpty()) {
            throw new IllegalArgumentException("可回答 case 的 expectedDocuments 不能为空");
        }
    }

    public RetrievalEvaluationCase(String id, String query, String language,
                                   List<String> expectedDocuments, List<String> expectedPassages,
                                   List<String> mustNotReturn, String category) {
        this(id, query, language, expectedDocuments, expectedPassages, mustNotReturn,
                category, "test", List.of(), true);
    }

    public boolean isAnswerable() {
        return Boolean.TRUE.equals(answerable);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
        return value;
    }
}
