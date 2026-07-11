package com.simplerag.application.runtime;

import com.simplerag.model.IndexStatus;

import java.util.EnumSet;
import java.util.Set;

/** Central policy for legal active-index state transitions. */
public final class IndexLifecycle {
    public void restore(ActiveKnowledgeContext next) {
        requireCoherent(next);
    }

    public void beginBuild(ActiveKnowledgeContext current, ActiveKnowledgeContext next) {
        requireSameKnowledgeBase(current, next);
        requireStatus(next, IndexStatus.BUILDING);
    }

    public void invalidate(ActiveKnowledgeContext current, ActiveKnowledgeContext next) {
        requireSameKnowledgeBase(current, next);
        requireStatus(next, IndexStatus.DIRTY, IndexStatus.INCOMPATIBLE);
    }

    public void publish(ActiveKnowledgeContext current, ActiveKnowledgeContext next) {
        requireSameIdentity(current, next);
        requireStatus(next, IndexStatus.READY);
        if (next.knowledgeBase().publishedIndexRevision() == null
                || next.knowledgeBase().publishedIndexRevision() != next.sourceRevision()) {
            throw new IllegalStateException("READY requires the current source revision to be published");
        }
    }

    public void fail(ActiveKnowledgeContext current, ActiveKnowledgeContext next) {
        requireSameKnowledgeBase(current, next);
        requireStatus(next, IndexStatus.FAILED, IndexStatus.DIRTY);
    }

    public void refresh(ActiveKnowledgeContext current, ActiveKnowledgeContext next) {
        requireSameKnowledgeBase(current, next);
        requireCoherent(next);
    }

    private static void requireSameKnowledgeBase(ActiveKnowledgeContext current, ActiveKnowledgeContext next) {
        if (!current.knowledgeBaseId().equals(next.knowledgeBaseId())) {
            throw new IllegalStateException("active knowledge base cannot change during a lifecycle transition");
        }
        requireCoherent(next);
    }

    private static void requireSameIdentity(ActiveKnowledgeContext current, ActiveKnowledgeContext next) {
        requireSameKnowledgeBase(current, next);
        if (current.sourceRevision() != next.sourceRevision()) {
            throw new IllegalStateException("index publication identity changed during build");
        }
    }

    private static void requireStatus(ActiveKnowledgeContext context, IndexStatus... expected) {
        Set<IndexStatus> allowed = EnumSet.noneOf(IndexStatus.class);
        java.util.Collections.addAll(allowed, expected);
        if (!allowed.contains(context.indexStatus())) {
            throw new IllegalStateException("illegal index lifecycle target: " + context.indexStatus());
        }
        requireCoherent(context);
    }

    private static void requireCoherent(ActiveKnowledgeContext context) {
        if (context == null) throw new IllegalArgumentException("active context is required");
    }
}
