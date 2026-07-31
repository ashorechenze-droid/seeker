package com.simplerag.application.dto;

import com.simplerag.model.TokenUsage;

import java.util.List;

public record AskResultView(String text, List<CitationView> citations, String model, TokenUsage usage) {
    public AskResultView {
        citations = List.copyOf(citations);
        usage = usage == null ? TokenUsage.UNKNOWN : usage;
    }

    public AskResultView(String text, List<CitationView> citations, String model) {
        this(text, citations, model, TokenUsage.UNKNOWN);
    }
}
