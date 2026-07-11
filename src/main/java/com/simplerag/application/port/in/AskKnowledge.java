package com.simplerag.application.port.in;

import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public interface AskKnowledge {
    RagAnswer askStream(String knowledgeBaseId, long expectedRevision, String question, ApiConfig config,
                        Consumer<List<RagCitation>> onCitations, Consumer<String> onDelta)
            throws IOException, InterruptedException;
}
