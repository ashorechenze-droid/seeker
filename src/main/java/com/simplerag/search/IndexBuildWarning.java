package com.simplerag.search;

import java.nio.file.Path;

/** A non-fatal document-level build diagnostic. */
public record IndexBuildWarning(Path path, String readerId, String message, boolean skipped) {
    public IndexBuildWarning {
        readerId = readerId == null || readerId.isBlank() ? "unknown" : readerId;
        message = message == null || message.isBlank() ? "无法读取文档" : message.strip();
    }
}
