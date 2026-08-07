package com.simplerag.adapter.in.swing;

import com.simplerag.rag.ApiConfig;
import com.simplerag.rag.ModelApiConfig;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsPanelTest {
    @Test
    void storesAllThreeConfigsAndRoutesModelFetchByKind() throws Exception {
        AtomicReference<SettingsPanel> reference = new AtomicReference<>();
        AtomicReference<SettingsPanel.ModelKind> requested = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SettingsPanel panel = new SettingsPanel(() -> { }, (kind, button) -> requested.set(kind));
            panel.configs(new ApiConfig("https://chat/v1", "c", "chat"),
                    new ModelApiConfig(true, "https://embed/v1", "e", "embed", 768),
                    new ModelApiConfig(true, "https://rerank/v1", "r", "rerank", 0));
            panel.models(SettingsPanel.ModelKind.EMBEDDING, List.of("embed-a", "embed-b"), "embed-b");
            java.util.List<javax.swing.JButton> fetchButtons = new java.util.ArrayList<>();
            collectFetchButtons(panel, fetchButtons);
            fetchButtons.get(1).doClick();
            reference.set(panel);
        });

        SettingsPanel panel = reference.get();
        assertEquals("chat", panel.chatConfig().model());
        assertEquals("embed-b", panel.embeddingConfig().model());
        assertEquals(768, panel.embeddingConfig().dimensions());
        assertTrue(panel.rerankConfig().enabled());

        // The callback contract carries the model kind, so each button can use its own endpoint.
        assertEquals(SettingsPanel.ModelKind.EMBEDDING, requested.get());
    }

    @Test
    void normalizesFullEmbeddingAndRerankEndpointsBeforeListingModels() {
        ApiConfig embedding = DesktopWorkspaceController.modelListConfig(
                new ApiConfig("https://provider.example/v1/embeddings", "key", "embed"),
                SettingsPanel.ModelKind.EMBEDDING);
        ApiConfig rerank = DesktopWorkspaceController.modelListConfig(
                new ApiConfig("https://provider.example/v1/rerank", "key", "rerank"),
                SettingsPanel.ModelKind.RERANK);

        assertEquals("https://provider.example/v1", embedding.baseUrl());
        assertEquals("https://provider.example/v1", rerank.baseUrl());
    }

    private static void collectFetchButtons(Component component, List<javax.swing.JButton> result) {
        if (component instanceof javax.swing.JButton button && "获取模型".equals(button.getText())) result.add(button);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectFetchButtons(child, result);
        }
    }
}
