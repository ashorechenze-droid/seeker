package com.simplerag.application.diagnostics;

import java.util.Map;

@FunctionalInterface
public interface DiagnosticSink {
    void record(String type, String category, String message, Map<String, String> attributes);

    default void record(String type, String category, String message) {
        record(type, category, message, Map.of());
    }

    static DiagnosticSink noop() {
        return (type, category, message, attributes) -> { };
    }
}
