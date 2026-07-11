package com.simplerag.search;

import java.io.Serial;
import java.io.Serializable;

public record IndexManifest(
        String knowledgeBaseId,
        long sourceRevision,
        String sourceSetHash,
        String embeddingModelSignature,
        int embeddingDimension,
        int chunkingVersion,
        int indexFormatVersion,
        long builtAt
) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
