package com.simplerag.application.conversation;

import com.simplerag.model.TokenUsage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The model's bounded decision: answer with current evidence, or run one more round of local
 * searches using queries it generated itself.
 *
 * <p>{@link Action#UNAVAILABLE} is deliberately distinct from {@link Action#ANSWER}. Both stop the
 * loop, but only ANSWER means the model judged the evidence sufficient; UNAVAILABLE means we never
 * got a usable decision (unreachable, truncated, or malformed). Collapsing the two hid planner
 * breakage behind answers that looked confident but were built on first-round evidence only.
 */
public record RetrievalDecision(Action action, List<String> queries, TokenUsage usage) {
    /** One planning round may fan out this many generated queries. */
    public static final int MAX_QUERIES = 3;
    private static final int MAX_QUERY_LENGTH = 500;

    public enum Action { SEARCH, ANSWER, UNAVAILABLE }

    public RetrievalDecision {
        Objects.requireNonNull(action, "action");
        queries = action == Action.SEARCH ? normalize(queries) : List.of();
        if (action == Action.SEARCH && queries.isEmpty()) {
            throw new IllegalArgumentException("At least one search query is required");
        }
        usage = usage == null ? TokenUsage.UNKNOWN : usage;
    }

    /** Trims, drops blanks, caps length, removes case-insensitive duplicates, bounds the fan-out. */
    private static List<String> normalize(List<String> raw) {
        if (raw == null) return List.of();
        List<String> cleaned = new ArrayList<>(Math.min(raw.size(), MAX_QUERIES));
        Set<String> seen = new LinkedHashSet<>();
        for (String candidate : raw) {
            if (candidate == null) continue;
            String query = candidate.strip();
            if (query.isEmpty()) continue;
            if (query.length() > MAX_QUERY_LENGTH) query = query.substring(0, MAX_QUERY_LENGTH).strip();
            if (!seen.add(query.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))) continue;
            cleaned.add(query);
            if (cleaned.size() >= MAX_QUERIES) break;
        }
        return List.copyOf(cleaned);
    }

    public static RetrievalDecision search(String... queries) {
        return new RetrievalDecision(Action.SEARCH, List.of(queries), TokenUsage.UNKNOWN);
    }

    public static RetrievalDecision search(List<String> queries) {
        return new RetrievalDecision(Action.SEARCH, queries, TokenUsage.UNKNOWN);
    }

    public static RetrievalDecision answer() {
        return new RetrievalDecision(Action.ANSWER, List.of(), TokenUsage.UNKNOWN);
    }

    /** No usable decision was obtained. Carries usage so a failed round is still billed honestly. */
    public static RetrievalDecision unavailable() {
        return new RetrievalDecision(Action.UNAVAILABLE, List.of(), TokenUsage.UNKNOWN);
    }

    /** Attaches what this planning round actually cost, so a turn can report its real total. */
    public RetrievalDecision withUsage(TokenUsage measured) {
        return new RetrievalDecision(action, queries, measured);
    }

    public boolean shouldSearch() {
        return action == Action.SEARCH;
    }

    /** True when the loop stopped because planning failed rather than because evidence sufficed. */
    public boolean plannerUnavailable() {
        return action == Action.UNAVAILABLE;
    }

    /** The first generated query, or empty for non-search decisions. */
    public String query() {
        return queries.isEmpty() ? "" : queries.get(0);
    }
}
