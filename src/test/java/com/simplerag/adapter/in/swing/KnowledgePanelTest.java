package com.simplerag.adapter.in.swing;

import com.simplerag.model.IndexStatus;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.KnowledgeStats;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KnowledgePanelTest {
    @Test
    void canOwnKnowledgeAndSourceSelectionWithoutMainFrame() throws Exception {
        AtomicReference<KnowledgePanel> panel = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            KnowledgePanel created = new KnowledgePanel(() -> { }, () -> { }, () -> { }, () -> { },
                    event -> { }, () -> { }, () -> { });
            KnowledgeBase kb = new KnowledgeBase("kb", "Docs", "Local", 1, 1, 2, null,
                    IndexStatus.DIRTY, null, null, null, "changed");
            created.knowledgeBases(List.of(kb), kb);
            created.sources(List.of(Path.of("docs")));
            created.stats(new KnowledgeStats(1, 3, 1, Path.of("index")));
            panel.set(created);
        });

        assertEquals("kb", panel.get().selectedKnowledgeBase().id());
        assertEquals(1, panel.get().sourceCount());
    }
}
