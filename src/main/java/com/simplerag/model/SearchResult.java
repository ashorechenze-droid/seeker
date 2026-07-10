package com.simplerag.model;

public record SearchResult(DocumentChunk chunk, double score, String reason) {
}
