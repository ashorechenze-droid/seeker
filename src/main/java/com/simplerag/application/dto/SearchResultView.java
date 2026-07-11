package com.simplerag.application.dto;

public record SearchResultView(DocumentReference document, double score, String reason) {
}
