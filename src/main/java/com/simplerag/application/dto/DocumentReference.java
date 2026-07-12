package com.simplerag.application.dto;

import java.nio.file.Path;

/** UI-safe document location and preview data without exposing retrieval internals. */
public record DocumentReference(String id, Path path, String fileName, String extension,
                                int startLine, int endLine, String sourceLocation,
                                String content, boolean semanticAvailable) {
    public DocumentReference {
        sourceLocation = sourceLocation == null || sourceLocation.isBlank()
                ? "L" + startLine + "-" + endLine : sourceLocation;
    }

    public DocumentReference(String id, Path path, String fileName, String extension,
                             int startLine, int endLine, String content, boolean semanticAvailable) {
        this(id, path, fileName, extension, startLine, endLine,
                "L" + startLine + "-" + endLine, content, semanticAvailable);
    }
}
