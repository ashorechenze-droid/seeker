package com.simplerag.bootstrap;

import com.simplerag.adapter.out.filesystem.FileSystemIndexRepository;
import com.simplerag.adapter.in.swing.AskController;
import com.simplerag.adapter.in.swing.KnowledgeController;
import com.simplerag.adapter.in.swing.SearchController;
import com.simplerag.application.usecase.KnowledgeService;
import com.simplerag.adapter.out.onnx.Langchain4jOnnxEmbeddingProvider;
import com.simplerag.adapter.out.openai.OpenAiCompatibleClient;
import com.simplerag.adapter.out.sqlite.AppRepository;
import com.simplerag.adapter.out.sqlite.DatabaseManager;
import com.simplerag.adapter.out.security.SecretCodec;
import com.simplerag.adapter.in.swing.MainFrame;
import com.simplerag.adapter.in.swing.ThemeBootstrap;

import javax.swing.SwingUtilities;
import java.nio.file.Path;

public final class AppCompositionRoot {
    public void start() {
        ThemeBootstrap.install();
        DatabaseManager database = new DatabaseManager();
        AppRepository sqlite = new AppRepository(database);
        KnowledgeService service = new KnowledgeService(
                new Langchain4jOnnxEmbeddingProvider(), sqlite, sqlite, new SecretCodec(),
                new OpenAiCompatibleClient(), new FileSystemIndexRepository(
                Path.of(System.getProperty("user.home"), ".simplerag", "indexes")));
        service.restore();
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame(
                    new KnowledgeController(service, service, service, service),
                    new SearchController(service), new AskController(service, service));
            frame.setVisible(true);
            frame.initializeKnowledge(Path.of("examples", "knowledge"));
        });
    }
}
