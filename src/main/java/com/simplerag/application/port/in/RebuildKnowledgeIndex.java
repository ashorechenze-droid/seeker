package com.simplerag.application.port.in;

import com.simplerag.application.dto.IndexBuildProgress;
import com.simplerag.application.dto.IndexBuildResult;

import java.io.IOException;
import java.util.function.Consumer;

public interface RebuildKnowledgeIndex {
    IndexBuildResult rebuildCurrent(Consumer<IndexBuildProgress> progress) throws IOException;
}
