package com.simplerag.application.port.in;

import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.dto.SearchResultView;
import com.simplerag.model.SemanticHighlight;

import java.io.IOException;
import java.util.List;

public interface SearchKnowledge {
    List<SearchResultView> search(String knowledgeBaseId, long expectedRevision, String query, int limit, String extension);
    List<SemanticHighlight> semanticHighlights(String knowledgeBaseId, long expectedRevision,
                                               String query, DocumentReference document, int limit) throws IOException;
}
