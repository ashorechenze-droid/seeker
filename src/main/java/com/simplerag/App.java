package com.simplerag;

import com.simplerag.service.KnowledgeService;
import com.simplerag.ui.MainFrame;
import com.simplerag.ui.ThemeBootstrap;

import javax.swing.SwingUtilities;
import java.nio.file.Path;

public final class App {
    private App() {
    }

    public static void main(String[] args) {
        ThemeBootstrap.install();
        KnowledgeService service = new KnowledgeService();
        service.restore();
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame(service);
            frame.setVisible(true);
            frame.initializeKnowledge(Path.of("examples", "knowledge"));
        });
    }
}
