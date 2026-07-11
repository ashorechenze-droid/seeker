package com.simplerag.application.usecase;

import com.simplerag.application.port.in.ManageKnowledgeBases;
import com.simplerag.model.KnowledgeBase;

import java.io.IOException;
import java.util.List;

/** Knowledge-base lifecycle input adapter with a deliberately small dependency surface. */
public final class KnowledgeBaseUseCases implements ManageKnowledgeBases {
    private final ManageKnowledgeBases delegate;

    public KnowledgeBaseUseCases(ManageKnowledgeBases delegate) { this.delegate = delegate; }
    @Override public List<KnowledgeBase> knowledgeBases() { return delegate.knowledgeBases(); }
    @Override public KnowledgeBase currentKnowledgeBase() { return delegate.currentKnowledgeBase(); }
    @Override public KnowledgeBase createKnowledgeBase(String name, String description) {
        return delegate.createKnowledgeBase(name, description);
    }
    @Override public KnowledgeBase updateCurrentKnowledgeBase(String name, String description) {
        return delegate.updateCurrentKnowledgeBase(name, description);
    }
    @Override public void deleteKnowledgeBase(String id) throws IOException { delegate.deleteKnowledgeBase(id); }
    @Override public boolean selectKnowledgeBase(String id) { return delegate.selectKnowledgeBase(id); }
}
