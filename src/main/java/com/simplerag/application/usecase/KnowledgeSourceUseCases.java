package com.simplerag.application.usecase;

import com.simplerag.application.port.in.ManageKnowledgeSources;

import java.nio.file.Path;
import java.util.List;

public final class KnowledgeSourceUseCases implements ManageKnowledgeSources {
    private final ManageKnowledgeSources delegate;
    public KnowledgeSourceUseCases(ManageKnowledgeSources delegate) { this.delegate = delegate; }
    @Override public void addSource(Path path) { delegate.addSource(path); }
    @Override public void removeSource(Path path) { delegate.removeSource(path); }
    @Override public List<Path> roots() { return delegate.roots(); }
}
