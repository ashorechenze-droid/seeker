package com.simplerag.search;

import com.simplerag.common.crypto.Digests;

import java.io.IOException;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;

/** Stable file identity used by incremental index planning. */
public record FileFingerprint(
        String root,
        String relativePath,
        long size,
        long modifiedAt,
        String contentHash
) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * System property forcing a full content hash for every file, bypassing the size/mtime fast path.
     * Consistency tests use it to prove both paths agree.
     */
    public static final String VERIFY_CONTENT_HASH_PROPERTY = "simplerag.index.verifyContentHash";

    public FileFingerprint {
        root = root == null ? "" : root;
        relativePath = relativePath == null ? "" : relativePath.replace('\\', '/');
        contentHash = contentHash == null ? "" : contentHash;
    }

    public static FileFingerprint capture(DocumentScanner.ScannedDocument document) throws IOException {
        return capture(document, null);
    }

    /**
     * Captures a fingerprint, reusing the published content hash when size and modification time are
     * both unchanged. Files that would be re-read anyway still get a real hash, so the only saving is
     * the redundant full read of files that are about to be reused verbatim.
     */
    public static FileFingerprint capture(DocumentScanner.ScannedDocument document,
                                          DocumentIndexEntry previous) throws IOException {
        Path root = document.root().toAbsolutePath().normalize();
        Path path = document.path().toAbsolutePath().normalize();
        String relativePath = root.relativize(path).toString();
        long size = Files.size(path);
        long modifiedAt = Files.getLastModifiedTime(path).toMillis();
        String contentHash = reusableHash(previous, size, modifiedAt);
        return new FileFingerprint(root.toString(), relativePath, size, modifiedAt,
                contentHash == null ? Digests.sha256File(path) : contentHash);
    }

    private static String reusableHash(DocumentIndexEntry previous, long size, long modifiedAt) {
        if (previous == null || Boolean.getBoolean(VERIFY_CONTENT_HASH_PROPERTY)) return null;
        if (previous.size() != size || previous.modifiedAt() != modifiedAt) return null;
        String hash = previous.contentHash();
        return hash == null || hash.isBlank() ? null : hash;
    }

    public String key() {
        return root + "\0" + relativePath;
    }

    /** Same identity as {@link #key()}, derivable before any file content is hashed. */
    public static String key(DocumentScanner.ScannedDocument document) {
        Path root = document.root().toAbsolutePath().normalize();
        Path path = document.path().toAbsolutePath().normalize();
        return root + "\0" + root.relativize(path).toString().replace('\\', '/');
    }
}
