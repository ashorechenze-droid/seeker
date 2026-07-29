package com.simplerag.application.conversation;

import java.util.Objects;

/** The model's bounded decision: answer with current evidence or perform one more local search. */
public record RetrievalDecision(Action action, String query) {
    public enum Action { SEARCH, ANSWER }

    public RetrievalDecision {
        Objects.requireNonNull(action, "action");
        query = query == null ? "" : query.strip();
        if (action == Action.SEARCH && query.isEmpty()) {
            throw new IllegalArgumentException("A search query is required");
        }
        if (query.length() > 500) query = query.substring(0, 500).strip();
        if (action == Action.ANSWER) query = "";
    }

    public static RetrievalDecision search(String query) {
        return new RetrievalDecision(Action.SEARCH, query);
    }

    public static RetrievalDecision answer() {
        return new RetrievalDecision(Action.ANSWER, "");
    }

    public boolean shouldSearch() {
        return action == Action.SEARCH;
    }
}