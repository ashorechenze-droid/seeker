package com.simplerag.search;

import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.model.DocumentChunk;

import java.util.List;

public final class SemanticScorer {
    public boolean compatible(TextEmbedder embedder, IndexManifest manifest, List<DocumentChunk> chunks) {
        if (!embedder.isConfigured() || manifest == null || manifest.embeddingDimension() <= 0
                || !embedder.signature().value().equals(manifest.embeddingModelSignature())) return false;
        return chunks.stream().filter(DocumentChunk::hasEmbedding)
                .allMatch(chunk -> chunk.embedding().length == manifest.embeddingDimension());
    }

    public boolean queryCompatible(float[] query, List<DocumentChunk> chunks) {
        if (query == null || query.length == 0) return false;
        return chunks.stream().filter(DocumentChunk::hasEmbedding)
                .allMatch(chunk -> chunk.embedding().length == query.length);
    }

    public double score(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length || left.length == 0) return 0;
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return leftNorm == 0 || rightNorm == 0 ? 0 : dot / Math.sqrt(leftNorm * rightNorm);
    }
}
