package com.simplerag.application.dto;

import java.util.List;

/**
 * Exact metadata shown before a remote RAG request is allowed to leave the machine.
 *
 * <p>Authorization is granted once per turn, so the review states both what is being sent right now
 * ({@code citations}) and the ceiling the adaptive retrieval loop may still reach within this turn
 * ({@code maxSearches} / {@code maxCitations}).
 */
public record RemoteSendReview(String knowledgeBaseId, String knowledgeBaseName, long sourceRevision,
                               String targetHost, boolean trustedHost, List<CitationView> citations,
                               int maxSearches, int maxCitations) {
    public RemoteSendReview {
        citations = List.copyOf(citations);
    }

    public int chunkCount() { return citations.size(); }
}
