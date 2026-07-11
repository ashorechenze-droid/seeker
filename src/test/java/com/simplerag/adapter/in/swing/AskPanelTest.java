package com.simplerag.adapter.in.swing;

import com.simplerag.rag.ApiConfig;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AskPanelTest {
    @Test
    void canBeConstructedAndConfiguredWithoutMainFrame() throws Exception {
        AtomicReference<AskPanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AskPanel created = new AskPanel(() -> { }, () -> { }, button -> { }, () -> { });
            created.config(new ApiConfig("http://localhost:11434/v1", "secret", "local-model"));
            panel.set(created);
        });

        assertEquals("local-model", panel.get().config().model());
        assertEquals("http://localhost:11434/v1", panel.get().config().baseUrl());
    }
}
