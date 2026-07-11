package com.simplerag.application.dto;

import java.nio.file.Path;

/** UI-safe document location and preview data without exposing retrieval internals. */
public record DocumentReference(String id, Path path, String fileName, String extension,
                                int startLine, int endLine, String content, boolean semanticAvailable) {
}
