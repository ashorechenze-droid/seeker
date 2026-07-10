package com.simplerag.model;

public record RagCitation(int number, DocumentChunk chunk, double score) {
    public String label() {
        return "[" + number + "] " + chunk.fileName() + " · L" + chunk.startLine() + "-" + chunk.endLine();
    }
}
