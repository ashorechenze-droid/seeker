package com.simplerag.common.crypto;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** JDK-only digest helpers shared by indexing, adapters and local security code. */
public final class Digests {
    private static final int DEFAULT_BUFFER_SIZE = 128 * 1024;

    private Digests() {
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", impossible);
        }
    }

    public static byte[] sha256Utf8(String value) {
        return sha256().digest(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256File(Path path) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    public static String hex(byte[] value) {
        return HexFormat.of().formatHex(value);
    }
}
