package com.simplerag.application.conversation;

import com.simplerag.common.text.TextValues;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory conversation sessions keyed by knowledgeBaseId.
 * SQLite persistence is intentionally out of scope for this commit.
 * A session is replaced when sourceRevision changes so old citations cannot be reused.
 */
public final class ConversationStore {
    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final ConversationContext contextPolicy;

    public ConversationStore() {
        this(ConversationContext.defaults());
    }

    public ConversationStore(ConversationContext contextPolicy) {
        this.contextPolicy = Objects.requireNonNull(contextPolicy, "contextPolicy");
    }

    public ConversationSession openOrReplace(String knowledgeBaseId, long sourceRevision) {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        String key = TextValues.trimToEmpty(knowledgeBaseId);
        return sessions.compute(key, (ignored, existing) -> {
            if (existing != null && existing.matches(key, sourceRevision)) {
                return existing;
            }
            return new ConversationSession(key, sourceRevision, contextPolicy);
        });
    }

    public Optional<ConversationSession> find(String knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.get(TextValues.trimToEmpty(knowledgeBaseId)));
    }

    public ConversationSession requireMatching(String knowledgeBaseId, long sourceRevision) {
        ConversationSession session = openOrReplace(knowledgeBaseId, sourceRevision);
        if (!session.matches(knowledgeBaseId, sourceRevision)) {
            throw new IllegalStateException("会话与当前知识库版本不一致，请重新开始对话");
        }
        return session;
    }

    public void clear(String knowledgeBaseId) {
        if (knowledgeBaseId == null) {
            return;
        }
        sessions.remove(TextValues.trimToEmpty(knowledgeBaseId));
    }

    public void clearAll() {
        sessions.clear();
    }

    public int sessionCount() {
        return sessions.size();
    }
}
