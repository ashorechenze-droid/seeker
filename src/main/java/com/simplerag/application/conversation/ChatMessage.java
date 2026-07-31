package com.simplerag.application.conversation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One turn in a multi-turn conversation. Citations are not retained in history. */
public record ChatMessage(String id, Role role, String content, Instant createdAt) {
    public enum Role {
        USER,
        ASSISTANT,
        SYSTEM
    }

    public ChatMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(createdAt, "createdAt");
        content = content.strip();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(UUID.randomUUID().toString(), Role.USER, content, Instant.now());
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT, content, Instant.now());
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(UUID.randomUUID().toString(), Role.SYSTEM, content, Instant.now());
    }

    /**
     * Script-aware token estimate. Budgeting normally goes through {@link TokenEstimator} so it can
     * apply the model's calibrated correction; this uncalibrated form is the raw baseline.
     */
    public int estimatedTokens() {
        return Math.max(1, TokenEstimator.rawTokens(content));
    }
}
