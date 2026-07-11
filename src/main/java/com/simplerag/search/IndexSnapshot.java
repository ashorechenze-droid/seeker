package com.simplerag.search;

import com.simplerag.model.DocumentChunk;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

public record IndexSnapshot(int version, List<String> roots, List<DocumentChunk> chunks, long indexedAt,
                            String embeddingModel, IndexManifest manifest,
                            List<DocumentIndexEntry> documentEntries)
        implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public static final int CURRENT_VERSION = 4;

    public IndexSnapshot {
        roots = roots == null ? List.of() : List.copyOf(roots);
        chunks = chunks == null ? List.of() : List.copyOf(chunks);
        documentEntries = documentEntries == null ? List.of() : List.copyOf(documentEntries);
    }

    public IndexSnapshot(int version, List<String> roots, List<DocumentChunk> chunks, long indexedAt,
                         String embeddingModel, IndexManifest manifest) {
        this(version, roots, chunks, indexedAt, embeddingModel, manifest, List.of());
    }
}
