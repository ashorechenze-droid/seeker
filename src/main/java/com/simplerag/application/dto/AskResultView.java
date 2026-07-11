package com.simplerag.application.dto;

import java.util.List;

public record AskResultView(String text, List<CitationView> citations, String model) {
    public AskResultView {
        citations = List.copyOf(citations);
    }
}
