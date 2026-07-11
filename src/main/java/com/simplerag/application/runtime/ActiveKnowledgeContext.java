package com.simplerag.application.runtime;

import com.simplerag.application.freshness.FreshnessSnapshot;
import com.simplerag.model.IndexStatus;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.search.IndexHandle;

import java.util.Objects;

/** Immutable snapshot of every piece of active knowledge-base runtime state. */
public record ActiveKnowledgeContext(KnowledgeBase knowledgeBase,
                                     FreshnessSnapshot freshness,
                                     IndexHandle indexHandle) {
    public ActiveKnowledgeContext {
        Objects.requireNonNull(knowledgeBase, "knowledgeBase");
        Objects.requireNonNull(freshness, "freshness");
        Objects.requireNonNull(indexHandle, "indexHandle");
        if (!knowledgeBase.id().equals(indexHandle.knowledgeBaseId())
                || knowledgeBase.sourceRevision() != indexHandle.sourceRevision()
                || knowledgeBase.indexStatus() != indexHandle.status()) {
            throw new IllegalArgumentException("knowledge-base metadata and index handle must have one identity");
        }
    }

    public String knowledgeBaseId() {
        return knowledgeBase.id();
    }

    public long sourceRevision() {
        return knowledgeBase.sourceRevision();
    }

    public IndexStatus indexStatus() {
        return knowledgeBase.indexStatus();
    }
}
