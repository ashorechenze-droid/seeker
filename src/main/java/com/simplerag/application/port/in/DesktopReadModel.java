package com.simplerag.application.port.in;

import com.simplerag.model.IndexStatus;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.KnowledgeStats;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public interface DesktopReadModel {
    KnowledgeBase currentKnowledgeBase();
    List<KnowledgeBase> knowledgeBases();
    List<Path> roots();
    KnowledgeStats stats();
    Set<String> extensions();
    boolean semanticEnabled();
    boolean semanticModelConfigured();
    String semanticStatus();
    String freshnessStatus();
    IndexStatus indexStatus();
    long sourceRevision();
    Path databasePath();
}
