package com.simplerag.model;

import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Path;
import java.time.Instant;

public record DocumentChunk(
        String id,
        String path,
        String root,
        String fileName,
        String extension,
        int startLine,
        int endLine,
        String sourceLocation,
        String content,
        long modifiedAt,
        float[] embedding
) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public DocumentChunk {
        sourceLocation = sourceLocation == null || sourceLocation.isBlank()
                ? "L" + startLine + "-" + endLine : sourceLocation;
    }

    public DocumentChunk(String id, String path, String root, String fileName, String extension,
                         int startLine, int endLine, String content, long modifiedAt, float[] embedding) {
        this(id, path, root, fileName, extension, startLine, endLine,
                "L" + startLine + "-" + endLine, content, modifiedAt, embedding);
    }

    public Path filePath() {
        return Path.of(path);
    }

    public Instant modifiedInstant() {
        return Instant.ofEpochMilli(modifiedAt);
    }

    public DocumentChunk withEmbedding(float[] value) {
        return new DocumentChunk(id, path, root, fileName, extension, startLine, endLine,
                sourceLocation, content, modifiedAt, value == null ? null : value.clone());
    }

    public boolean hasEmbedding() {
        return embedding != null && embedding.length > 0;
    }
}
