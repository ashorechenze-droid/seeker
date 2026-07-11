package com.simplerag.application.runtime;

import com.simplerag.application.freshness.FreshnessSnapshot;
import com.simplerag.application.freshness.FreshnessState;
import com.simplerag.model.IndexStatus;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.search.IndexHandle;
import com.simplerag.search.SemanticSearchEngine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ActiveKnowledgeRuntimeTest {
    private final SemanticSearchEngine engine = new SemanticSearchEngine(null);
    private final ActiveKnowledgeRuntime runtime = new ActiveKnowledgeRuntime(new IndexLifecycle());

    @Test
    void publishesOnlyTheCapturedRevision() {
        runtime.restore(context(IndexStatus.DIRTY, 3, null));
        runtime.beginBuild(context(IndexStatus.BUILDING, 3, null));
        runtime.publish(context(IndexStatus.READY, 3, 3L));

        assertEquals(IndexStatus.READY, runtime.current().indexStatus());
        assertEquals(3, runtime.current().sourceRevision());
    }

    @Test
    void rejectsReadyWithoutMatchingPublishedRevision() {
        runtime.restore(context(IndexStatus.BUILDING, 4, null));

        assertThrows(IllegalStateException.class,
                () -> runtime.publish(context(IndexStatus.READY, 4, 3L)));
    }

    @Test
    void rejectsCrossKnowledgeBaseLifecycleMutation() {
        runtime.restore(context("kb-a", IndexStatus.DIRTY, 1, null));

        assertThrows(IllegalStateException.class,
                () -> runtime.beginBuild(context("kb-b", IndexStatus.BUILDING, 1, null)));
    }

    private ActiveKnowledgeContext context(IndexStatus status, long revision, Long published) {
        return context("kb-a", status, revision, published);
    }

    private ActiveKnowledgeContext context(String id, IndexStatus status, long revision, Long published) {
        KnowledgeBase kb = new KnowledgeBase(id, id, "", 1, 1, revision, published, status,
                null, null, null, null);
        FreshnessSnapshot freshness = new FreshnessSnapshot(id, revision, FreshnessState.STOPPED,
                0, "", 0, "not verified", 0);
        return new ActiveKnowledgeContext(kb, freshness, new IndexHandle(id, revision, status, engine));
    }
}
