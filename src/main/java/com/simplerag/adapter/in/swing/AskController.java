package com.simplerag.adapter.in.swing;

import com.simplerag.application.port.in.AskKnowledge;
import com.simplerag.application.port.in.ManageApiSettings;
import com.simplerag.application.dto.AskResultView;
import com.simplerag.application.dto.CitationView;
import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public final class AskController {
    private final AskKnowledge ask;
    private final ManageApiSettings settings;

    public AskController(AskKnowledge ask, ManageApiSettings settings) {
        this.ask = ask;
        this.settings = settings;
    }

    public ApiConfig config() { return settings.apiConfig(); }
    public void saveConfig(ApiConfig config) { settings.saveApiConfig(config); }
    public List<String> fetchModels(ApiConfig config) throws IOException, InterruptedException {
        return settings.fetchModels(config);
    }
    public AskResultView ask(KnowledgeController.TaskIdentity identity, String question, ApiConfig config,
                         Consumer<List<CitationView>> onCitations, Consumer<String> onDelta)
            throws IOException, InterruptedException {
        return ask.askStream(identity.knowledgeBaseId(), identity.sourceRevision(), question, config,
                onCitations, onDelta);
    }
}
