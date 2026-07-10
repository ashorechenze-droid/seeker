package com.simplerag.search;

import com.simplerag.model.DocumentChunk;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

public record IndexSnapshot(int version, List<String> roots, List<DocumentChunk> chunks, long indexedAt,
                            String embeddingModel)
        implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final int CURRENT_VERSION = 2;
}
