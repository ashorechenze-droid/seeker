package com.simplerag.adapter.in.swing;

import com.simplerag.application.port.in.SearchKnowledge;
import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.dto.SearchResultView;
import com.simplerag.model.SemanticHighlight;

import java.io.IOException;
import java.util.List;

public final class SearchController {
    private final SearchKnowledge search;

    public SearchController(SearchKnowledge search) {
        this.search = search;
    }

    public List<SearchResultView> search(KnowledgeController.TaskIdentity identity, String query,
                                     int limit, String extension) {
        return search.search(identity.knowledgeBaseId(), identity.sourceRevision(), query, limit, extension);
    }

    public List<SemanticHighlight> highlights(KnowledgeController.TaskIdentity identity, String query,
                                              DocumentReference document, int limit) throws IOException {
        return search.semanticHighlights(identity.knowledgeBaseId(), identity.sourceRevision(), query, document, limit);
    }
}
