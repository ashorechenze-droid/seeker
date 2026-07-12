package com.simplerag.search;

/** One source-addressable text unit such as a line, paragraph, slide line or worksheet row. */
public record DocumentTextUnit(int number, String text) {
    public DocumentTextUnit {
        if (number < 1) throw new IllegalArgumentException("source unit number must be positive");
        text = text == null ? "" : text;
    }
}
