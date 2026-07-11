package com.simplerag.adapter.in.swing;

import com.simplerag.application.port.in.DesktopReadModel;
import com.simplerag.application.port.in.ManageKnowledgeBases;
import com.simplerag.application.port.in.ManageKnowledgeSources;
import com.simplerag.application.port.in.RebuildKnowledgeIndex;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.KnowledgeStats;
import com.simplerag.search.SemanticSearchEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

public final class KnowledgeController {
    private final ManageKnowledgeBases knowledgeBases;
    private final ManageKnowledgeSources sources;
    private final RebuildKnowledgeIndex rebuild;
    private final DesktopReadModel readModel;

    public KnowledgeController(ManageKnowledgeBases knowledgeBases, ManageKnowledgeSources sources,
                               RebuildKnowledgeIndex rebuild, DesktopReadModel readModel) {
        this.knowledgeBases = knowledgeBases;
        this.sources = sources;
        this.rebuild = rebuild;
        this.readModel = readModel;
    }

    public List<KnowledgeBase> knowledgeBases() { return knowledgeBases.knowledgeBases(); }
    public KnowledgeBase current() { return readModel.currentKnowledgeBase(); }
    public KnowledgeBase create(String name, String description) { return knowledgeBases.createKnowledgeBase(name, description); }
    public KnowledgeBase updateCurrent(String name, String description) { return knowledgeBases.updateCurrentKnowledgeBase(name, description); }
    public void delete(String id) throws IOException { knowledgeBases.deleteKnowledgeBase(id); }
    public boolean select(String id) { return knowledgeBases.selectKnowledgeBase(id); }
    public List<Path> sources() { return sources.roots(); }
    public void addSource(Path path) { sources.addSource(path); }
    public void removeSource(Path path) { sources.removeSource(path); }
    public SemanticSearchEngine.IndexReport rebuild(Consumer<SemanticSearchEngine.IndexProgress> progress)
            throws IOException { return rebuild.rebuildCurrent(progress); }
    public KnowledgeStats stats() { return readModel.stats(); }
    public Set<String> extensions() { return readModel.extensions(); }
    public boolean semanticEnabled() { return readModel.semanticEnabled(); }
    public boolean semanticModelConfigured() { return readModel.semanticModelConfigured(); }
    public String semanticStatus() { return readModel.semanticStatus(); }
    public TaskIdentity identity() {
        KnowledgeBase current = readModel.currentKnowledgeBase();
        return new TaskIdentity(current.id(), readModel.sourceRevision());
    }

    public record TaskIdentity(String knowledgeBaseId, long sourceRevision) { }
}
