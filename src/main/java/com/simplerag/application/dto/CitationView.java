package com.simplerag.application.dto;

public record CitationView(int number, DocumentReference document, double score) {
    public String label() {
        return "[" + number + "] " + document.fileName() + " · L"
                + document.startLine() + "-" + document.endLine();
    }
}
