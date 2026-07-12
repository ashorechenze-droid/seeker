package com.simplerag.application.conversation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Limits multi-turn history so context cannot grow without bound.
 * Uses a coarse char/4 token estimate — good enough for local budgeting.
 */
public final class ConversationContext {
    public static final int DEFAULT_MAX_TURNS = 12;
    public static final int DEFAULT_MAX_HISTORY_TOKENS = 3_000;

    private final int maxTurns;
    private final int maxHistoryTokens;

    public ConversationContext(int maxTurns, int maxHistoryTokens) {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns 必须 >= 1");
        }
        if (maxHistoryTokens < 64) {
            throw new IllegalArgumentException("maxHistoryTokens 必须 >= 64");
        }
        this.maxTurns = maxTurns;
        this.maxHistoryTokens = maxHistoryTokens;
    }

    public static ConversationContext defaults() {
        return new ConversationContext(DEFAULT_MAX_TURNS, DEFAULT_MAX_HISTORY_TOKENS);
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
        while (estimatedTokens(window) > maxHistoryTokens && window.size() > 1) {
            window.remove(0);
            while (!window.isEmpty() && window.get(0).role() != ChatMessage.Role.USER) {
                window.remove(0);
            }
        }
        if (estimatedTokens(window) > maxHistoryTokens && window.size() == 1) {
            // Single oversized message: keep a truncated tail so the model still gets a signal.
            ChatMessage only = window.get(0);
            int keepChars = Math.max(64, maxHistoryTokens * 4);
            if (only.content().length() > keepChars) {
                String truncated = "…" + only.content().substring(only.content().length() - keepChars);
                return List.of(new ChatMessage(only.id(), only.role(), truncated, only.createdAt()));
            }
        }
        return List.copyOf(window);
    }

    public static int estimatedTokens(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage message : messages) {
            total += message.estimatedTokens();
        }
        return total;
    }
}
