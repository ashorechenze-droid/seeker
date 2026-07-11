package com.simplerag.application.port.in;

import com.simplerag.application.dto.AskResultView;
import com.simplerag.application.dto.CitationView;
import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public interface AskKnowledge {
    AskResultView askStream(String knowledgeBaseId, long expectedRevision, String question, ApiConfig config,
                        Consumer<List<CitationView>> onCitations, Consumer<String> onDelta)
            throws IOException, InterruptedException;
}
