package com.simplerag.application.conversation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationModulesTest {
    @Test
    void historyIsBoundToKnowledgeBaseAndRevision() {
        ConversationStore store = new ConversationStore();
        ConversationSession first = store.openOrReplace("kb-1", 1L);
        first.appendUser("hello");
        first.appendAssistant("hi");

        ConversationSession same = store.openOrReplace("kb-1", 1L);
        assertSame(first, same);
        assertEquals(2, same.size());

        ConversationSession revised = store.openOrReplace("kb-1", 2L);
        assertNotSame(first, revised);
        assertTrue(revised.isEmpty());
        assertFalse(revised.matches("kb-1", 1L));
    }

    @Test
    void historyForRequestExcludesNothingWhenLastIsAssistant() {
        ConversationSession session = new ConversationSession("kb", 1L);
        session.appendUser("q1");
        session.appendAssistant("a1");
        List<ChatMessage> history = session.historyForRequest(false);
        assertEquals(2, history.size());
        assertEquals(ChatMessage.Role.USER, history.get(0).role());
    }

    @Test
    void contextTrimsByTurnAndTokenBudget() {
        ConversationContext policy = new ConversationContext(4, 200);
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            messages.add(ChatMessage.user("question-" + i + "-" + "x".repeat(20)));
            messages.add(ChatMessage.assistant("answer-" + i + "-" + "y".repeat(20)));
        }
        List<ChatMessage> trimmed = policy.trim(messages);
        assertTrue(trimmed.size() <= 4);
        assertTrue(ConversationContext.estimatedTokens(trimmed) <= 200);
        assertEquals(ChatMessage.Role.USER, trimmed.get(0).role());
    }

    @Test
    void chatRequestRejectsSystemHistoryAndCopiesLists() {
        assertThrows(IllegalArgumentException.class, () ->
                new ChatRequest("kb", 1L, "q", List.of(ChatMessage.system("nope")), List.of()));
        ChatRequest request = new ChatRequest("kb", 1L, "q",
                List.of(ChatMessage.user("prior")), List.of());
        assertEquals(1, request.history().size());
        assertThrows(UnsupportedOperationException.class, () -> request.history().add(ChatMessage.user("x")));
    }

    @Test
    void sessionDoesNotRetainCitationsInMessages() {
        ConversationSession session = new ConversationSession("kb", 3L);
        session.appendUser("What is the port?");
        session.appendAssistant("It is 3306 [1].");
        for (ChatMessage message : session.messages()) {
            assertFalse(message.content().contains("file://"));
            assertTrue(message.role() == ChatMessage.Role.USER || message.role() == ChatMessage.Role.ASSISTANT);
        }
    }
}