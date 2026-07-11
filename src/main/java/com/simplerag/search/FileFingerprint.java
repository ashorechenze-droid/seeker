package com.simplerag.search;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serial;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
                Files.getLastModifiedTime(path).toMillis(), sha256(path));
    }

    public String key() {
        return root + "\0" + relativePath;
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", impossible);
        }
    }
}
