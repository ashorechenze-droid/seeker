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

    public FileFingerprint {
        root = root == null ? "" : root;
        relativePath = relativePath == null ? "" : relativePath.replace('\\', '/');
        contentHash = contentHash == null ? "" : contentHash;
    }

    public static FileFingerprint capture(DocumentScanner.ScannedDocument document) throws IOException {
        Path root = document.root().toAbsolutePath().normalize();
        Path path = document.path().toAbsolutePath().normalize();
        return new FileFingerprint(root.toString(), root.relativize(path).toString(), Files.size(path),
                Files.getLastModifiedTime(path).toMillis(), Digests.sha256File(path));
    }

    public String key() {
        return root + "\0" + relativePath;
    }
}
