package com.simplerag.adapter.in.swing;

import com.simplerag.rag.ApiConfig;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskPanelTest {
    @Test
    void canBeConstructedAndConfiguredWithoutMainFrame() throws Exception {
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, button -> { }, () -> { }, () -> { });
            created.config(new ApiConfig("http://localhost:11434/v1", "secret", "local-model"));
            panel.set(created);
        });

        assertEquals("local-model", panel.get().config().model());
        assertEquals("http://localhost:11434/v1", panel.get().config().baseUrl());
    }

    @Test
    void transcriptAndLatestAnswerAreAvailableAsCopyablePlainText() throws Exception {
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, button -> { }, () -> { }, () -> { });
            created.beginTurn("登录校验代码在哪？");
            created.appendAssistantDelta("实现位于 AuthService.java [1]。");
            created.finishAssistant("实现位于 AuthService.java [1]。", "test-model");
            panel.set(created);
        });

        assertTrue(panel.get().conversationText().contains("你：\n登录校验代码在哪？"));
        assertTrue(panel.get().conversationText().contains("助手：\n实现位于 AuthService.java [1]。"));
        assertEquals("实现位于 AuthService.java [1]。", panel.get().latestAnswerWithCitations());
    }
}
