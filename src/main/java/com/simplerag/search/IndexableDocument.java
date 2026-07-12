package com.simplerag.search;

/** Current file fingerprint plus the exact reader implementation identity used to interpret it. */
public record IndexableDocument(FileFingerprint fingerprint, String readerId, int readerVersion) {
    public IndexableDocument {
        if (fingerprint == null) throw new IllegalArgumentException("fingerprint is required");
        readerId = readerId == null ? "" : readerId;
    }

    public String key() { return fingerprint.key(); }
}
