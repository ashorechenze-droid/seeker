package com.simplerag.application.diagnostics;

import java.util.Map;
import java.util.LinkedHashMap;

/** A deliberately metadata-only local diagnostic event. */
public record DiagnosticEvent(long timestamp, String type, String category, String message,
                              Map<String, String> attributes) {
    public DiagnosticEvent {
        type = safe(type);
        category = safe(category);
        message = safe(message);
        if (attributes == null || attributes.isEmpty()) {
            attributes = Map.of();
        } else {
            Map<String, String> sanitized = new LinkedHashMap<>();
            attributes.forEach((key, value) -> sanitized.put(safe(key), sensitiveKey(key) ? "[REDACTED]" : safe(value)));
            attributes = Map.copyOf(sanitized);
        }
    }

    private static String safe(String value) {
        if (value == null) return "";
        String result = value
                .replaceAll("(?i)bearer\\s+\\S+", "Bearer [REDACTED]")
                .replaceAll("(?i)(api[_ -]?key|authorization)\\s*[:=]\\s*\\S+", "$1=[REDACTED]");
        return result.length() <= 500 ? result : result.substring(0, 500) + "…";
    }

    private static boolean sensitiveKey(String key) {
        if (key == null) return false;
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("key") || normalized.contains("authorization")
                || normalized.contains("prompt") || normalized.contains("content")
                || normalized.contains("chunktext") || normalized.contains("body");
    }
}
