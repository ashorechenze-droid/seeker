package com.simplerag.application.conversation;

import java.util.Objects;

/** One local knowledge-base search performed during an agentic retrieval turn. */
public record RetrievalAttempt(String query, int addedCitations) {
    public RetrievalAttempt {
        Objects.requireNonNull(query, "query");
        query = query.strip();
        if (query.isEmpty()) throw new IllegalArgumentException("Retrieval query must not be blank");
        if (addedCitations < 0) throw new IllegalArgumentException("Added citation count must not be negative");
    }
}