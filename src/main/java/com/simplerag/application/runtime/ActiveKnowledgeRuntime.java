package com.simplerag.application.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** The single writer and atomic read boundary for active knowledge-base runtime state. */
public final class ActiveKnowledgeRuntime {
    private final AtomicReference<ActiveKnowledgeContext> active = new AtomicReference<>();
    private final IndexLifecycle lifecycle;

    public ActiveKnowledgeRuntime(IndexLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    public ActiveKnowledgeContext current() {
        ActiveKnowledgeContext context = active.get();
        if (context == null) throw new IllegalStateException("尚未选择知识库");
        return context;
    }

    public ActiveKnowledgeContext currentOrNull() {
        return active.get();
    }

    public synchronized void restore(ActiveKnowledgeContext next) {
        lifecycle.restore(next);
        active.set(next);
    }

    public synchronized void beginBuild(ActiveKnowledgeContext next) {
        lifecycle.beginBuild(current(), next);
        active.set(next);
    }

    public synchronized void invalidate(ActiveKnowledgeContext next) {
        lifecycle.invalidate(current(), next);
        active.set(next);
    }

    public synchronized void publish(ActiveKnowledgeContext next) {
        lifecycle.publish(current(), next);
        active.set(next);
    }

    public synchronized void fail(ActiveKnowledgeContext next) {
        lifecycle.fail(current(), next);
        active.set(next);
    }

    public synchronized void refresh(ActiveKnowledgeContext next) {
        lifecycle.refresh(current(), next);
        active.set(next);
    }
}
