package com.simplerag.application.usecase;

import com.simplerag.application.conversation.ChatMessage;
import com.simplerag.application.conversation.RetrievalAttempt;
import com.simplerag.application.conversation.RetrievalDecision;
import com.simplerag.application.conversation.RetrievalPlanRequest;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.RagCitation;
import com.simplerag.model.SearchResult;
import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Runs a bounded, adaptive retrieve-evaluate-retrieve loop before answer generation. */
final class IterativeRetrieval {
    static final int MAX_SEARCHES = 4;
    static final int MAX_CITATIONS = 12;
    private static final int INITIAL_CITATIONS = 6;
    private static final int FOLLOW_UP_CITATIONS = 3;

    private final ChatModel chat;

    IterativeRetrieval(ChatModel chat) {
        this.chat = chat;
    }

    List<RagCitation> collect(String knowledgeBaseId, long sourceRevision, String question,
                              List<ChatMessage> history, ApiConfig config,
                              Function<String, List<SearchResult>> search,
                              Consumer<List<RagCitation>> onCitationScopeChanged,
                              Runnable beforeRemoteCall)
            throws IOException, InterruptedException {
        Map<String, Candidate> collected = new LinkedHashMap<>();
        List<RetrievalAttempt> attempts = new ArrayList<>();
        int initialAdded = addResults(collected, search.apply(question), INITIAL_CITATIONS);
        attempts.add(new RetrievalAttempt(question, initialAdded));
        List<RagCitation> citations = numbered(collected);
        onCitationScopeChanged.accept(citations);

        for (int searchNumber = 1; searchNumber < MAX_SEARCHES; searchNumber++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Question answering was cancelled");
            beforeRemoteCall.run();
            RetrievalPlanRequest request = new RetrievalPlanRequest(knowledgeBaseId, sourceRevision,
                    question, history, citations, attempts, MAX_SEARCHES - searchNumber);
            RetrievalDecision decision = chat.planRetrieval(config, request);
            if (decision == null || !decision.shouldSearch()) break;

            String query = decision.query();
            if (alreadyTried(attempts, query)) break;
            int added = addResults(collected, search.apply(query), FOLLOW_UP_CITATIONS);
            attempts.add(new RetrievalAttempt(query, added));
            if (added > 0) {
                citations = numbered(collected);
                onCitationScopeChanged.accept(citations);
            }
            if (collected.size() >= MAX_CITATIONS) break;
        }
        return citations;
    }

    private static int addResults(Map<String, Candidate> collected, List<SearchResult> results, int limit) {
        int added = 0;
        if (results == null) return 0;
        for (SearchResult result : results) {
            if (result == null || result.chunk() == null) continue;
            DocumentChunk chunk = result.chunk();
            String key = chunk.id() + "\u0000" + chunk.path() + "\u0000" + chunk.sourceLocation();
            if (collected.containsKey(key)) continue;
            collected.put(key, new Candidate(chunk, result.score()));
            added++;
            if (added >= limit || collected.size() >= MAX_CITATIONS) break;
        }
        return added;
    }

    private static List<RagCitation> numbered(Map<String, Candidate> collected) {
        List<RagCitation> citations = new ArrayList<>(collected.size());
        int number = 1;
        for (Candidate candidate : collected.values()) {
            citations.add(new RagCitation(number++, candidate.chunk(), candidate.score()));
        }
        return List.copyOf(citations);
    }

    private static boolean alreadyTried(List<RetrievalAttempt> attempts, String query) {
        String normalized = query.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return attempts.stream().map(RetrievalAttempt::query)
                .map(value -> value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT))
                .anyMatch(normalized::equals);
    }

    private record Candidate(DocumentChunk chunk, double score) { }
}
