package com.simplerag.application.conversation;

import com.simplerag.model.TokenUsage;

import java.util.Objects;

/** The model's bounded decision: answer with current evidence or perform one more local search. */
public record RetrievalDecision(Action action, String query, TokenUsage usage) {
    public enum Action { SEARCH, ANSWER }

    public RetrievalDecision {
        Objects.requireNonNull(action, "action");
        query = query == null ? "" : query.strip();
        if (action == Action.SEARCH && query.isEmpty()) {
            throw new IllegalArgumentException("A search query is required");
        }
        if (query.length() > 500) query = query.substring(0, 500).strip();
        if (action == Action.ANSWER) query = "";
        usage = usage == null ? TokenUsage.UNKNOWN : usage;
    }

    public RetrievalDecision(Action action, String query) {
        this(action, query, TokenUsage.UNKNOWN);
    }

    public static RetrievalDecision search(String query) {
        return new RetrievalDecision(Action.SEARCH, query);
    }

    public static RetrievalDecision answer() {
        return new RetrievalDecision(Action.ANSWER, "");
    }

    /** Attaches what this planning round actually cost, so a turn can report its real total. */
    public RetrievalDecision withUsage(TokenUsage measured) {
        return new RetrievalDecision(action, query, measured);
    }

    public boolean shouldSearch() {
        return action == Action.SEARCH;
    }
}
