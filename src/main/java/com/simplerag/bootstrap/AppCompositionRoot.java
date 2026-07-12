package com.simplerag.bootstrap;

import com.simplerag.adapter.out.filesystem.FileSystemIndexRepository;
import com.simplerag.adapter.out.filesystem.FileSystemSourceFreshnessMonitor;
import com.simplerag.adapter.in.swing.AskController;
import com.simplerag.adapter.in.swing.KnowledgeController;
import com.simplerag.adapter.in.swing.SearchController;
import com.simplerag.application.usecase.KnowledgeService;
import com.simplerag.application.usecase.ApiSettingsUseCase;
import com.simplerag.application.usecase.AskUseCase;
import com.simplerag.application.usecase.DesktopQueryService;
import com.simplerag.application.usecase.IndexBuildUseCase;
import com.simplerag.application.usecase.KnowledgeBaseUseCases;
import com.simplerag.application.usecase.KnowledgeSourceUseCases;
import com.simplerag.application.usecase.SearchUseCase;
import com.simplerag.application.runtime.ActiveKnowledgeRuntime;
import com.simplerag.application.runtime.IndexLifecycle;
import com.simplerag.application.freshness.FreshnessGate;
import com.simplerag.adapter.out.onnx.Langchain4jOnnxEmbeddingProvider;
import com.simplerag.adapter.out.openai.OpenAiCompatibleClient;
import com.simplerag.adapter.out.sqlite.AppRepository;
import com.simplerag.adapter.out.sqlite.DatabaseManager;
import com.simplerag.adapter.out.security.SecretCodec;
import com.simplerag.adapter.out.security.WindowsCredentialManagerSecretStore;
import com.simplerag.adapter.out.diagnostics.InMemoryDiagnosticLog;
import com.simplerag.application.diagnostics.DiagnosticReportService;
import com.simplerag.adapter.in.swing.MainFrame;
import com.simplerag.adapter.in.swing.ThemeBootstrap;
import com.simplerag.adapter.in.swing.BackgroundTaskCoordinator;
import com.simplerag.adapter.in.swing.SystemDesktopFileGateway;

import javax.swing.SwingUtilities;
import java.nio.file.Path;

public final class AppCompositionRoot {
    public void start() {
        ThemeBootstrap.install();
        DatabaseManager database = new DatabaseManager();
        AppRepository sqlite = new AppRepository(database);
        Langchain4jOnnxEmbeddingProvider embeddings = new Langchain4jOnnxEmbeddingProvider();
        InMemoryDiagnosticLog diagnostics = new InMemoryDiagnosticLog();
        WindowsCredentialManagerSecretStore secrets = new WindowsCredentialManagerSecretStore(
                new SecretCodec(), diagnostics);
        OpenAiCompatibleClient chat = new OpenAiCompatibleClient(diagnostics);
        FileSystemSourceFreshnessMonitor freshness = new FileSystemSourceFreshnessMonitor();
        ActiveKnowledgeRuntime runtime = new ActiveKnowledgeRuntime(new IndexLifecycle(), diagnostics);
        KnowledgeService service = new KnowledgeService(
                embeddings, sqlite, sqlite, secrets, chat, new FileSystemIndexRepository(
                Path.of(System.getProperty("user.home"), ".simplerag", "indexes")),
                freshness, runtime, diagnostics);
        Runtime.getRuntime().addShutdownHook(new Thread(service::close, "simplerag-shutdown"));
        service.restore();
        KnowledgeBaseUseCases knowledgeBases = new KnowledgeBaseUseCases(service);
        KnowledgeSourceUseCases sources = new KnowledgeSourceUseCases(service);
        IndexBuildUseCase indexBuild = new IndexBuildUseCase(service);
        SearchUseCase search = new SearchUseCase(runtime);
        AskUseCase ask = new AskUseCase(runtime, sqlite, new FreshnessGate(freshness), chat, sqlite, diagnostics);
        ApiSettingsUseCase apiSettings = new ApiSettingsUseCase(sqlite, secrets, chat);
        DesktopQueryService desktopQueries = new DesktopQueryService(service);
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame(
                    new KnowledgeController(knowledgeBases, sources, indexBuild, desktopQueries),
                    new SearchController(search), new AskController(ask, apiSettings),
                    new BackgroundTaskCoordinator(), new SystemDesktopFileGateway(),
                    new DiagnosticReportService(runtime, diagnostics));
            frame.setVisible(true);
            frame.initializeKnowledge(Path.of("examples", "knowledge"));
        });
    }
}
