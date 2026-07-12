package com.simplerag.application.usecase;

import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.ChatRequest;
import com.simplerag.application.dto.AskResultView;
import com.simplerag.application.dto.CitationView;
import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.port.in.AskKnowledge;
import com.simplerag.application.freshness.FreshnessGate;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.application.port.out.KnowledgeBaseRepository;
import com.simplerag.application.runtime.ActiveKnowledgeRuntime;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.IndexStatus;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.model.SearchResult;
import com.simplerag.search.IndexHandle;
import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

public final class AskUseCase implements AskKnowledge {
    private final ActiveKnowledgeRuntime runtime;
    private final KnowledgeBaseRepository knowledgeBases;
    private final FreshnessGate freshnessGate;
    private final ChatModel chat;

    public AskUseCase(ActiveKnowledgeRuntime runtime, KnowledgeBaseRepository knowledgeBases,
                      FreshnessGate freshnessGate, ChatModel chat) {
        this.runtime = runtime;
        this.knowledgeBases = knowledgeBases;
        this.freshnessGate = freshnessGate;
        this.chat = chat;
    }

    @Override
    public AskResultView askStream(String knowledgeBaseId, long expectedRevision, String question,
                                   List<ChatMessage> history, ApiConfig config,
                                   Consumer<List<CitationView>> onCitations, Consumer<String> onDelta)
            throws IOException, InterruptedException {
        IndexHandle handle = requireReady(knowledgeBaseId, expectedRevision);
        // Every turn re-runs retrieval; history must never reintroduce prior citation snippets.
        List<RagCitation> citations = retrieve(handle, question);
        List<CitationView> views = citations.stream().map(AskUseCase::toView).toList();
        if (onCitations != null) onCitations.accept(views);
        freshnessGate.requireFresh(handle.knowledgeBaseId(), handle.sourceRevision());
        List<ChatMessage> safeHistory = history == null ? List.of() : List.copyOf(history);
        ChatRequest request = new ChatRequest(handle.knowledgeBaseId(), handle.sourceRevision(),
                question, safeHistory, citations);
        RagAnswer answer = chat.answerStream(config, request, onDelta);
        return new AskResultView(answer.text(), views, answer.model());
    }

    private IndexHandle requireReady(String knowledgeBaseId, long revision) {
        IndexHandle handle = runtime.current().indexHandle();
        if (!handle.knowledgeBaseId().equals(knowledgeBaseId) || handle.sourceRevision() != revision) {
            throw new StaleTaskException("知识库或数据版本已变化，任务结果已丢弃");
        }
        KnowledgeBase latest = knowledgeBases.findKnowledgeBase(knowledgeBaseId).orElseThrow();
        if (latest.indexStatus() != IndexStatus.READY || latest.publishedIndexRevision() == null
                || latest.sourceRevision() != revision || latest.publishedIndexRevision() != revision) {
            throw new IllegalStateException("当前索引不是最新 READY 状态，已禁止发送远程 RAG 请求");
        }
        freshnessGate.requireFresh(knowledgeBaseId, revision);
        return handle;
    }

    private static List<RagCitation> retrieve(IndexHandle handle, String question) {
        List<SearchResult> results = handle.engine().search(question, 8, "全部");
        return java.util.stream.IntStream.range(0, Math.min(6, results.size()))
                .mapToObj(index -> new RagCitation(index + 1, results.get(index).chunk(), results.get(index).score()))
                .toList();
    }

    private static CitationView toView(RagCitation citation) {
        DocumentChunk chunk = citation.chunk();
        DocumentReference document = new DocumentReference(chunk.id(), chunk.filePath(), chunk.fileName(),
                chunk.extension(), chunk.startLine(), chunk.endLine(), chunk.content(), chunk.hasEmbedding());
        return new CitationView(citation.number(), document, citation.score());
    }
}