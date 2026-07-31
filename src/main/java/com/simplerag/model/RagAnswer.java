package com.simplerag.model;

import java.util.List;

public record RagAnswer(String text, List<RagCitation> citations, String model, TokenUsage usage) {
    public RagAnswer {
        usage = usage == null ? TokenUsage.UNKNOWN : usage;
    }

    public RagAnswer(String text, List<RagCitation> citations, String model) {
        this(text, citations, model, TokenUsage.UNKNOWN);
    }
}
