package com.simplerag.search;

import java.nio.file.Path;
import java.util.List;

public record IndexBuildRequest(
        String knowledgeBaseId,
        long sourceRevision,
        List<Path> sources,
        String sourceSetHash,
        EmbeddingModelSignature modelSignature
) {
    public IndexBuildRequest {
        sources = List.copyOf(sources);
    }
}
