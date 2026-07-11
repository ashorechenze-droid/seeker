package com.simplerag.search;

import com.simplerag.model.IndexStatus;

public record IndexHandle(
        String knowledgeBaseId,
        long sourceRevision,
        IndexStatus status,
        SemanticSearchEngine engine
) {
}
