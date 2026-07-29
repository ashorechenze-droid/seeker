package com.simplerag.application.port.out;

import com.simplerag.application.conversation.ChatRequest;
import com.simplerag.application.conversation.RetrievalDecision;
import com.simplerag.application.conversation.RetrievalPlanRequest;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public interface ChatModel {
    List<String> listModels(ApiConfig config) throws IOException, InterruptedException;

    /**
     * Single-turn convenience: empty history, current question + citations only.
     * Prefer {@link #answer(ApiConfig, ChatRequest)} for multi-turn.
     */
    default RagAnswer answer(ApiConfig config, String question, List<RagCitation> citations)
            throws IOException, InterruptedException {
        return answer(config, new ChatRequest("legacy", 0L, question, List.of(), citations));
    }

    RagAnswer answer(ApiConfig config, ChatRequest request) throws IOException, InterruptedException;

    /**
     * Lets a capable model decide whether the current evidence needs another local retrieval.
     * The default preserves compatibility with simple/legacy adapters by finishing after the first search.
     */
    default RetrievalDecision planRetrieval(ApiConfig config, RetrievalPlanRequest request)
            throws IOException, InterruptedException {
        return RetrievalDecision.answer();
    }

    default RagAnswer answerStream(ApiConfig config, String question, List<RagCitation> citations,
                                   Consumer<String> onDelta) throws IOException, InterruptedException {
        return answerStream(config, new ChatRequest("legacy", 0L, question, List.of(), citations), onDelta);
    }

    RagAnswer answerStream(ApiConfig config, ChatRequest request, Consumer<String> onDelta)
            throws IOException, InterruptedException;
}
