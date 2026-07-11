package com.simplerag.application.port.in;

import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SearchResult;
import com.simplerag.model.SemanticHighlight;

import java.io.IOException;
import java.util.List;

public interface SearchKnowledge {
    List<SearchResult> search(String knowledgeBaseId, long expectedRevision, String query, int limit, String extension);
    List<SemanticHighlight> semanticHighlights(String knowledgeBaseId, long expectedRevision,
                                               String query, DocumentChunk chunk, int limit) throws IOException;
}
