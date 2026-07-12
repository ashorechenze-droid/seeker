package com.simplerag.application.conversation;

import com.simplerag.model.RagCitation;

import java.util.List;
import java.util.Objects;

/**
 * Immutable outbound request for one RAG chat completion.
 * History is prior user/assistant turns only; citations are for the current turn only.
 */
public record ChatRequest(
        String knowledgeBaseId,
        long sourceRevision,
        String question,
        List<ChatMessage> history,
        List<RagCitation> citations
) {
    public ChatRequest {
        Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
        Objects.requireNonNull(question, "question");
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(citations, "citations");
        knowledgeBaseId = knowledgeBaseId.strip();
        question = question.strip();
        if (knowledgeBaseId.isEmpty()) {
            throw new IllegalArgumentException("knowledgeBaseId 不能为空");
        }
        if (question.isEmpty()) {
            throw new IllegalArgumentException("请输入问题");
        }
        history = List.copyOf(history);
        citations = List.copyOf(citations);
        for (ChatMessage message : history) {
            if (message.role() == ChatMessage.Role.SYSTEM) {
                throw new IllegalArgumentException("历史中不能包含 system 消息");
            }
        }
    }
}
