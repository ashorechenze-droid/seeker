package com.simplerag.model;

import java.util.List;

public record RagAnswer(String text, List<RagCitation> citations, String model) {
}
