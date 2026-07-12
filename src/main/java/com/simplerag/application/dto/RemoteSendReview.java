package com.simplerag.application.dto;

import java.util.List;

/** Exact metadata shown before a remote RAG request is allowed to leave the machine. */
public record RemoteSendReview(String knowledgeBaseId, String knowledgeBaseName, long sourceRevision,
                               String targetHost, boolean trustedHost, List<CitationView> citations) {
    public RemoteSendReview {
        citations = List.copyOf(citations);
    }

    public int chunkCount() { return citations.size(); }
}
