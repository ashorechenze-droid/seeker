package com.simplerag.search;

import java.io.IOException;

/** Expected per-document extraction failure; callers may skip the file and continue the snapshot. */
public final class DocumentReadException extends IOException {
    public DocumentReadException(String message) { super(message); }
    public DocumentReadException(String message, Throwable cause) { super(message, cause); }
}
