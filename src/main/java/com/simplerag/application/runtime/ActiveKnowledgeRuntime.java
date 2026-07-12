package com.simplerag.application.runtime;

import com.simplerag.application.diagnostics.DiagnosticSink;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** The single writer and atomic read boundary for active knowledge-base runtime state. */
public final class ActiveKnowledgeRuntime {
    private final AtomicReference<ActiveKnowledgeContext> active = new AtomicReference<>();
    private final IndexLifecycle lifecycle;
    private final DiagnosticSink diagnostics;

    public ActiveKnowledgeRuntime(IndexLifecycle lifecycle) {
        this(lifecycle, DiagnosticSink.noop());
    }

    public ActiveKnowledgeRuntime(IndexLifecycle lifecycle, DiagnosticSink diagnostics) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.diagnostics = diagnostics == null ? DiagnosticSink.noop() : diagnostics;
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
        event("runtime restored", next);
    }

    public synchronized void beginBuild(ActiveKnowledgeContext next) {
        lifecycle.beginBuild(current(), next);
        active.set(next);
        event("index build started", next);
    }

    public synchronized void invalidate(ActiveKnowledgeContext next) {
        lifecycle.invalidate(current(), next);
        active.set(next);
        event("freshness changed", next);
    }

    public synchronized void publish(ActiveKnowledgeContext next) {
        lifecycle.publish(current(), next);
        active.set(next);
        event("index build completed", next);
    }

    public synchronized void fail(ActiveKnowledgeContext next) {
        lifecycle.fail(current(), next);
        active.set(next);
        event("index build failed", next);
    }

    public synchronized void refresh(ActiveKnowledgeContext next) {
        lifecycle.refresh(current(), next);
        ActiveKnowledgeContext previous = active.getAndSet(next);
        if (previous == null || previous.freshness().state() != next.freshness().state()
                || !Objects.equals(previous.freshness().reason(), next.freshness().reason())) {
            event("freshness changed", next);
        }
    }

    private void event(String type, ActiveKnowledgeContext context) {
        diagnostics.record(type, "runtime", context.indexStatus().name(), java.util.Map.of(
                "knowledgeBaseId", context.knowledgeBaseId(),
                "sourceRevision", Long.toString(context.sourceRevision()),
                "freshness", context.freshness().state().name()));
    }
}
