package com.simplerag.application.usecase;

import com.simplerag.application.conversation.ChatRequest;
import com.simplerag.application.conversation.RetrievalDecision;
import com.simplerag.application.conversation.RetrievalPlanRequest;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;
import com.simplerag.model.SearchResult;
import com.simplerag.rag.ApiConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IterativeRetrievalTest {
    private static final ApiConfig CONFIG = new ApiConfig("https://example.test/v1", "key", "model");

    @Test
    void modelCanAdaptivelySearchSeveralTimesAndScopesAreRepublished() throws Exception {
        PlanningChat chat = new PlanningChat(List.of(
                RetrievalDecision.search("beta implementation"),
                RetrievalDecision.search("gamma callers"),
                RetrievalDecision.answer()));
        IterativeRetrieval retrieval = new IterativeRetrieval(chat);
        List<String> queries = new ArrayList<>();
        List<Integer> publishedSizes = new ArrayList<>();
        AtomicInteger freshnessChecks = new AtomicInteger();

        List<RagCitation> result = retrieval.collect("kb", 7L, "how does it work?", List.of(), CONFIG,
                query -> {
                    queries.add(query);
                    if (query.startsWith("beta")) return List.of(hit("c2", 0.9), hit("c3", 0.8));
                    if (query.startsWith("gamma")) return List.of(hit("c4", 0.7));
                    return List.of(hit("c1", 1.0), hit("c2", 0.95));
                }, scope -> publishedSizes.add(scope.size()), freshnessChecks::incrementAndGet);

        assertEquals(List.of("how does it work?", "beta implementation", "gamma callers"), queries);
        assertEquals(List.of(2, 3, 4), publishedSizes);
        assertEquals(3, freshnessChecks.get());
        assertEquals(List.of("c1", "c2", "c3", "c4"),
                result.stream().map(citation -> citation.chunk().id()).toList());
        assertEquals(List.of(1, 2, 3, 4), result.stream().map(RagCitation::number).toList());
        assertEquals(List.of(2, 3, 4), chat.evidenceSizes);
    }

    @Test
    void retrievalStopsAtCitationBudgetEvenWhenModelKeepsSearching() throws Exception {
        ChatModel chat = new PlanningChat(List.of(
                RetrievalDecision.search("follow up 1"),
                RetrievalDecision.search("follow up 2"),
                RetrievalDecision.search("follow up 3")));
        IterativeRetrieval retrieval = new IterativeRetrieval(chat);
        AtomicInteger searchNumber = new AtomicInteger();

        List<RagCitation> result = retrieval.collect("kb", 1L, "initial", List.of(), CONFIG,
                query -> {
                    int batch = searchNumber.getAndIncrement();
                    List<SearchResult> hits = new ArrayList<>();
                    for (int i = 0; i < 8; i++) hits.add(hit("b" + batch + "-" + i, 1.0 - i / 10.0));
                    return hits;
                }, ignored -> { }, () -> { });

        assertEquals(12, result.size());
        assertEquals(3, searchNumber.get());
    }

    private static SearchResult hit(String id, double score) {
        DocumentChunk chunk = new DocumentChunk(id, "docs/" + id + ".md", "docs", id + ".md", ".md",
                1, 3, "content for " + id, 1L, null);
        return new SearchResult(chunk, score, "test");
    }

    private static final class PlanningChat implements ChatModel {
        private final List<RetrievalDecision> decisions;
        private final List<Integer> evidenceSizes = new ArrayList<>();
        private int next;

        private PlanningChat(List<RetrievalDecision> decisions) {
            this.decisions = decisions;
        }

        @Override public List<String> listModels(ApiConfig config) { return List.of("model"); }
        @Override public RagAnswer answer(ApiConfig config, ChatRequest request) {
            return new RagAnswer("answer", request.citations(), config.model());
        }
        @Override public RagAnswer answerStream(ApiConfig config, ChatRequest request, Consumer<String> onDelta) {
            return answer(config, request);
        }
        @Override public RetrievalDecision planRetrieval(ApiConfig config, RetrievalPlanRequest request) {
            evidenceSizes.add(request.citations().size());
            return next < decisions.size() ? decisions.get(next++) : RetrievalDecision.answer();
        }
    }
}