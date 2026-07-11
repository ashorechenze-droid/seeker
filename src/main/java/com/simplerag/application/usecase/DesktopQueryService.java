package com.simplerag.application.usecase;

import com.simplerag.application.port.in.DesktopReadModel;
import com.simplerag.model.IndexStatus;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.KnowledgeStats;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class DesktopQueryService implements DesktopReadModel {
    private final DesktopReadModel delegate;
    public DesktopQueryService(DesktopReadModel delegate) { this.delegate = delegate; }
    @Override public KnowledgeBase currentKnowledgeBase() { return delegate.currentKnowledgeBase(); }
    @Override public List<KnowledgeBase> knowledgeBases() { return delegate.knowledgeBases(); }
    @Override public List<Path> roots() { return delegate.roots(); }
    @Override public KnowledgeStats stats() { return delegate.stats(); }
    @Override public Set<String> extensions() { return delegate.extensions(); }
    @Override public boolean semanticEnabled() { return delegate.semanticEnabled(); }
    @Override public boolean semanticModelConfigured() { return delegate.semanticModelConfigured(); }
    @Override public String semanticStatus() { return delegate.semanticStatus(); }
    @Override public String freshnessStatus() { return delegate.freshnessStatus(); }
    @Override public IndexStatus indexStatus() { return delegate.indexStatus(); }
    @Override public long sourceRevision() { return delegate.sourceRevision(); }
    @Override public Path databasePath() { return delegate.databasePath(); }
}
