package com.simplerag.application.usecase;

import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.ChatRequest;
import com.simplerag.application.dto.AskResultView;
import com.simplerag.application.dto.CitationView;
import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.dto.RemoteSendReview;
import com.simplerag.application.diagnostics.DiagnosticSink;
import com.simplerag.application.port.in.AskKnowledge;
import com.simplerag.application.port.in.RemoteSendAuthorizer;
import com.simplerag.application.freshness.FreshnessGate;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.application.port.out.KnowledgeBaseRepository;
import com.simplerag.application.port.out.SettingsRepository;
import com.simplerag.application.runtime.ActiveKnowledgeRuntime;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.IndexStatus;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.search.IndexHandle;
import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import java.util.Map;

public final class AskUseCase implements AskKnowledge {
    private final ActiveKnowledgeRuntime runtime;
    private final KnowledgeBaseRepository knowledgeBases;
    private final FreshnessGate freshnessGate;
    private final ChatModel chat;
    private final SettingsRepository settings;
    private final DiagnosticSink diagnostics;

    public AskUseCase(ActiveKnowledgeRuntime runtime, KnowledgeBaseRepository knowledgeBases,
                      FreshnessGate freshnessGate, ChatModel chat) {
        this.runtime = runtime;
        this.knowledgeBases = knowledgeBases;
        this.freshnessGate = freshnessGate;
        this.chat = chat;
        this.settings = null;
        this.diagnostics = DiagnosticSink.noop();
    }

    public AskUseCase(ActiveKnowledgeRuntime runtime, KnowledgeBaseRepository knowledgeBases,
                      FreshnessGate freshnessGate, ChatModel chat, SettingsRepository settings,
                      DiagnosticSink diagnostics) {
        this.runtime = runtime;
        this.knowledgeBases = knowledgeBases;
        this.freshnessGate = freshnessGate;
        this.chat = chat;
        this.settings = settings;
        this.diagnostics = diagnostics == null ? DiagnosticSink.noop() : diagnostics;
    }

    @Override
    public AskResultView askStream(String knowledgeBaseId, long expectedRevision, String question,
                                   List<ChatMessage> history, ApiConfig config,
                                   Consumer<List<CitationView>> onCitations, RemoteSendAuthorizer authorizer,
                                   Consumer<String> onDelta)
            throws IOException, InterruptedException {
        IndexHandle handle = requireReady(knowledgeBaseId, expectedRevision);
        KnowledgeBase knowledgeBase = knowledgeBases.findKnowledgeBase(knowledgeBaseId).orElseThrow();
        if (localOnly(knowledgeBaseId)) {
            diagnostics.record("RAG blocked reason", "security", "knowledge base is local-only",
                    Map.of("knowledgeBaseId", knowledgeBaseId, "revision", Long.toString(expectedRevision)));
            throw new IllegalStateException("当前知识库启用了“仅本地 RAG”，已禁止远程发送");
        }
        // History only resolves conversational references. Every turn builds a fresh, bounded evidence set.
        List<ChatMessage> safeHistory = history == null ? List.of() : List.copyOf(history);
        IterativeRetrieval retrieval = new IterativeRetrieval(chat);
        List<RagCitation> citations = retrieval.collect(handle.knowledgeBaseId(), handle.sourceRevision(),
                question, safeHistory, config,
                query -> handle.engine().search(query, 8, "\u5168\u90e8"),
                scope -> publishAndAuthorizeScope(knowledgeBase, expectedRevision, config, scope,
                        onCitations, authorizer),
                () -> freshnessGate.requireFresh(handle.knowledgeBaseId(), handle.sourceRevision()));

        freshnessGate.requireFresh(handle.knowledgeBaseId(), handle.sourceRevision());
        List<CitationView> views = citations.stream().map(AskUseCase::toView).toList();
        ChatRequest request = new ChatRequest(handle.knowledgeBaseId(), handle.sourceRevision(),
                question, safeHistory, citations);
        RagAnswer answer = chat.answerStream(config, request, onDelta);
        return new AskResultView(answer.text(), views, answer.model());
    }

    private boolean localOnly(String knowledgeBaseId) {
        return settings != null && Boolean.parseBoolean(settings.getSetting("rag.local_only." + knowledgeBaseId)
                .orElse("false"));
    }

    private boolean trusted(String host) {
        if (settings == null) return false;
        return java.util.Arrays.stream(settings.getSetting("api.trusted_hosts").orElse("").split(","))
                .map(String::strip).anyMatch(host::equalsIgnoreCase);
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

    private void publishAndAuthorizeScope(KnowledgeBase knowledgeBase, long expectedRevision, ApiConfig config,
                                          List<RagCitation> citations,
                                          Consumer<List<CitationView>> onCitations,
                                          RemoteSendAuthorizer authorizer) {
        List<CitationView> views = citations.stream().map(AskUseCase::toView).toList();
        if (onCitations != null) onCitations.accept(views);
        freshnessGate.requireFresh(knowledgeBase.id(), expectedRevision);
        String host = config.targetHost();
        RemoteSendReview review = new RemoteSendReview(knowledgeBase.id(), knowledgeBase.name(), expectedRevision,
                host, trusted(host), views);
        if (authorizer == null || !authorizer.authorize(review)) {
            diagnostics.record("RAG blocked reason", "security", "remote send was not authorized",
                    Map.of("knowledgeBaseId", knowledgeBase.id(), "targetHost", host,
                            "chunkCount", Integer.toString(views.size())));
            throw new IllegalStateException("用户取消了远程发送");
        }
    }

    private static CitationView toView(RagCitation citation) {
        DocumentChunk chunk = citation.chunk();
        DocumentReference document = new DocumentReference(chunk.id(), chunk.filePath(), chunk.fileName(),
                chunk.extension(), chunk.startLine(), chunk.endLine(), chunk.sourceLocation(),
                chunk.content(), chunk.hasEmbedding());
        return new CitationView(citation.number(), document, citation.score());
    }
}
