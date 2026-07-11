package com.simplerag.application.usecase;

import com.simplerag.application.dto.IndexBuildProgress;
import com.simplerag.application.dto.IndexBuildResult;
import com.simplerag.application.port.in.RebuildKnowledgeIndex;

import java.io.IOException;
import java.util.function.Consumer;

public final class IndexBuildUseCase implements RebuildKnowledgeIndex {
    private final RebuildKnowledgeIndex delegate;
    public IndexBuildUseCase(RebuildKnowledgeIndex delegate) { this.delegate = delegate; }
    @Override public IndexBuildResult rebuildCurrent(Consumer<IndexBuildProgress> progress) throws IOException {
        return delegate.rebuildCurrent(progress);
    }
}
