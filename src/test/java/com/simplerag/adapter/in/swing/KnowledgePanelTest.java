package com.simplerag.adapter.in.swing;

import com.simplerag.model.IndexStatus;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.KnowledgeStats;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class KnowledgePanelTest {
    @Test
    void ownsKnowledgeSelectionAndHostsTheExplorerWithoutMainFrame() throws Exception {
        AtomicReference<KnowledgePanel> panel = new AtomicReference<>();
        JPanel explorer = new JPanel();
        SwingUtilities.invokeAndWait(() -> {
            KnowledgePanel created = new KnowledgePanel(() -> { }, () -> { }, () -> { }, () -> { },
                    () -> { }, explorer);
            KnowledgeBase kb = new KnowledgeBase("kb", "Docs", "Local", 1, 1, 2, null,
                    IndexStatus.DIRTY, null, null, null, "changed");
            created.knowledgeBases(List.of(kb), kb);
            created.stats(new KnowledgeStats(1, 3, 1, Path.of("index")));
            panel.set(created);
        });

        assertEquals("kb", panel.get().selectedKnowledgeBase().id());
        assertSame(explorer, ((java.awt.BorderLayout) panel.get().getLayout())
                .getLayoutComponent(java.awt.BorderLayout.CENTER));
    }
}
