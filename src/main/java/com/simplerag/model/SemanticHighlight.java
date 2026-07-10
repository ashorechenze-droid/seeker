package com.simplerag.model;

public record SemanticHighlight(int startOffset, int endOffset, double similarity) {
    public SemanticHighlight {
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new IllegalArgumentException("Invalid semantic highlight range");
        }
    }
}
