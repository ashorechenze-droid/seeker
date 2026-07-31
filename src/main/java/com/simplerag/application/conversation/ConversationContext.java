package com.simplerag.application.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Limits multi-turn history so context cannot grow without bound.
 * Budgeting runs through {@link TokenEstimator}, which is calibrated against the provider's real
 * reported usage rather than the old char/4 rule.
 */
public final class ConversationContext {
    public static final int DEFAULT_MAX_TURNS = 12;
    public static final int DEFAULT_MAX_HISTORY_TOKENS = 3_000;

    private final int maxTurns;
    private final int maxHistoryTokens;
    private final TokenEstimator estimator;

    public ConversationContext(int maxTurns, int maxHistoryTokens) {
        this(maxTurns, maxHistoryTokens, new TokenEstimator());
    }

    public ConversationContext(int maxTurns, int maxHistoryTokens, TokenEstimator estimator) {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns 必须 >= 1");
        }
        if (maxHistoryTokens < 64) {
            throw new IllegalArgumentException("maxHistoryTokens 必须 >= 64");
        }
        this.maxTurns = maxTurns;
        this.maxHistoryTokens = maxHistoryTokens;
        this.estimator = Objects.requireNonNull(estimator, "estimator");
    }

    public static ConversationContext defaults() {
        return new ConversationContext(DEFAULT_MAX_TURNS, DEFAULT_MAX_HISTORY_TOKENS);
    }

    public static ConversationContext defaults(TokenEstimator estimator) {
        return new ConversationContext(DEFAULT_MAX_TURNS, DEFAULT_MAX_HISTORY_TOKENS, estimator);
    }

    public int maxTurns() {
        return maxTurns;
    }

    public int maxHistoryTokens() {
        return maxHistoryTokens;
    }

    /**
     * Keeps the newest complete turns within turn and token budgets.
     * Prefer starting on a USER message so history pairs remain coherent.
     */
    public List<ChatMessage> trim(List<ChatMessage> messages) {
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> source = List.copyOf(messages);
        int from = Math.max(0, source.size() - maxTurns);
        List<ChatMessage> window = new ArrayList<>(source.subList(from, source.size()));
        while (!window.isEmpty() && window.get(0).role() != ChatMessage.Role.USER) {
            window.remove(0);
        }
        while (estimator.estimate(window) > maxHistoryTokens && window.size() > 1) {
            window.remove(0);
            while (!window.isEmpty() && window.get(0).role() != ChatMessage.Role.USER) {
                window.remove(0);
            }
        }
        if (estimator.estimate(window) > maxHistoryTokens && window.size() == 1) {
            // Single oversized message: keep a truncated tail so the model still gets a signal.
            ChatMessage only = window.get(0);
            int keepChars = Math.max(64, charsFor(maxHistoryTokens, only.content()));
            if (only.content().length() > keepChars) {
                String truncated = "…" + only.content().substring(only.content().length() - keepChars);
                return List.of(new ChatMessage(only.id(), only.role(), truncated, only.createdAt()));
            }
        }
        return List.copyOf(window);
    }

    /**
     * How many characters of this text fit in a token budget. Derived from the text's own measured
     * token density so a Chinese message is not cut as if it were English.
     */
    private int charsFor(int tokenBudget, String text) {
        int tokens = Math.max(1, estimator.estimate(text));
        double charsPerToken = Math.max(0.5, (double) text.length() / tokens);
        return (int) Math.floor(tokenBudget * charsPerToken);
    }

    public int estimatedTokens(List<ChatMessage> messages) {
        return estimator.estimate(messages);
    }

    /** Uncalibrated script-aware count, for callers without an estimator instance. */
    public static int rawTokens(List<ChatMessage> messages) {
        return TokenEstimator.rawMessageTokens(messages);
    }
}
