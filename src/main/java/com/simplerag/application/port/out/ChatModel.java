package com.simplerag.application.port.out;

import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public interface ChatModel {
    List<String> listModels(ApiConfig config) throws IOException, InterruptedException;
    RagAnswer answer(ApiConfig config, String question, List<RagCitation> citations)
            throws IOException, InterruptedException;
    RagAnswer answerStream(ApiConfig config, String question, List<RagCitation> citations,
                           Consumer<String> onDelta) throws IOException, InterruptedException;
}
