package com.simplerag.search;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** File-level manifest entry linking a fingerprint to its published chunk identities. */
public record DocumentIndexEntry(
        String root,
        String relativePath,
        long size,
        long modifiedAt,
        String contentHash,
        int readerVersion,
        int chunkingVersion,
        List<String> chunkIds
) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public DocumentIndexEntry {
        root = root == null ? "" : root;
        relativePath = relativePath == null ? "" : relativePath.replace('\\', '/');
        contentHash = contentHash == null ? "" : contentHash;
        chunkIds = chunkIds == null ? List.of() : List.copyOf(chunkIds);
    }

    public static DocumentIndexEntry from(FileFingerprint fingerprint, int readerVersion,
                                          int chunkingVersion, List<String> chunkIds) {
        return new DocumentIndexEntry(fingerprint.root(), fingerprint.relativePath(), fingerprint.size(),
                fingerprint.modifiedAt(), fingerprint.contentHash(), readerVersion, chunkingVersion, chunkIds);
    }

    public FileFingerprint fingerprint() {
        return new FileFingerprint(root, relativePath, size, modifiedAt, contentHash);
    }

    public String key() {
        return fingerprint().key();
    }
}
