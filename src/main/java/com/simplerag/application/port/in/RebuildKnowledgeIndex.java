package com.simplerag.application.port.in;

import com.simplerag.search.SemanticSearchEngine;

import java.io.IOException;
import java.util.function.Consumer;

public interface RebuildKnowledgeIndex {
    SemanticSearchEngine.IndexReport rebuildCurrent(Consumer<SemanticSearchEngine.IndexProgress> progress)
            throws IOException;
}
