package com.simplerag.application.conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * In-memory multi-turn session bound to a single knowledgeBaseId + sourceRevision.
 * When the binding changes, callers must open a new session rather than reuse history.
 */
public final class ConversationSession {
    private final String id;
    private final String knowledgeBaseId;
    private final long sourceRevision;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final ConversationContext contextPolicy;

    public ConversationSession(String knowledgeBaseId, long sourceRevision) {
        this(knowledgeBaseId, sourceRevision, ConversationContext.defaults());
    }

    public ConversationSession(String knowledgeBaseId, long sourceRevision, ConversationContext contextPolicy) {
        this.id = UUID.randomUUID().toString();
        this.knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId").strip();
        if (this.knowledgeBaseId.isEmpty()) {
            throw new IllegalArgumentException("knowledgeBaseId 不能为空");
        }
        this.sourceRevision = sourceRevision;
        this.contextPolicy = Objects.requireNonNull(contextPolicy, "contextPolicy");
    }

    public String id() {
        return id;
    }

    public String knowledgeBaseId() {
        return knowledgeBaseId;
    }

    public long sourceRevision() {
        return sourceRevision;
    }

    public synchronized boolean matches(String knowledgeBaseId, long sourceRevision) {
        return this.knowledgeBaseId.equals(knowledgeBaseId) && this.sourceRevision == sourceRevision;
    }

    public synchronized List<ChatMessage> messages() {
        return List.copyOf(messages);
    }

    public synchronized boolean isEmpty() {
        return messages.isEmpty();
    }

    public synchronized int size() {
        return messages.size();
    }

    public synchronized void appendUser(String content) {
        messages.add(ChatMessage.user(content));
        trimInPlace();
    }

    public synchronized void appendAssistant(String content) {
        messages.add(ChatMessage.assistant(content));
        trimInPlace();
    }

    public synchronized void clear() {
        messages.clear();
    }

    /**
     * Prior turns eligible for the next model call (excludes the current user question once appended).
     * Callers should snapshot history before appending the current user turn, or pass excludeLastUser=true.
     */
    public synchronized List<ChatMessage> historyForRequest(boolean excludeLastUser) {
        if (messages.isEmpty()) {
            return List.of();
        }
        List<ChatMessage> source = messages;
        if (excludeLastUser) {
            ChatMessage last = messages.get(messages.size() - 1);
            if (last.role() == ChatMessage.Role.USER) {
                source = messages.subList(0, messages.size() - 1);
            }
        }
        return contextPolicy.trim(source);
    }

    public synchronized List<ChatMessage> trimmedMessages() {
        return contextPolicy.trim(messages);
    }

    private void trimInPlace() {
        List<ChatMessage> kept = contextPolicy.trim(messages);
        if (kept.size() == messages.size()) {
            return;
        }
        messages.clear();
        messages.addAll(kept);
    }

    @Override
    public String toString() {
        return "ConversationSession{id='" + id + "', knowledgeBaseId='" + knowledgeBaseId
                + "', sourceRevision=" + sourceRevision + ", messages=" + messages.size() + '}';
    }
}
