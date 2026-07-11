package com.simplerag.search;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.nio.file.Files;
import java.util.Set;
import java.util.Locale;

public final class IndexIdentity {
    public static final int CHUNKING_VERSION = 1;
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", ".simplerag", "node_modules", "target", "build", "dist",
            "out", "vendor", ".venv", "venv", "__pycache__", ".next", ".gradle");

    private IndexIdentity() {
    }

    public static String sourceSetHash(List<Path> sources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Path source : sources.stream().map(Path::toAbsolutePath).map(Path::normalize)
                    .sorted(Comparator.comparing(Path::toString)).toList()) {
                update(digest, source.toString());
                if (!Files.exists(source)) {
                    update(digest, "missing");
                    continue;
                }
                if (!Files.isDirectory(source)) {
                    update(digest, "not-directory");
                    continue;
                }
                update(digest, "directory");
                try (var paths = Files.walk(source)) {
                    for (Path path : paths.filter(Files::isRegularFile)
                            .filter(path -> !containsIgnoredDirectory(source, path))
                            .sorted(Comparator.comparing(Path::toString)).toList()) {
                        try {
                            update(digest, source.relativize(path).toString());
                            update(digest, Long.toString(Files.size(path)));
                            update(digest, Long.toString(Files.getLastModifiedTime(path).toMillis()));
                        } catch (Exception unreadable) {
                            update(digest, "unreadable:" + path);
                        }
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception impossible) {
            throw new IllegalStateException("无法计算数据源签名", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static boolean containsIgnoredDirectory(Path root, Path path) {
        Path relative = root.relativize(path);
        for (Path part : relative) {
            if (IGNORED_DIRECTORIES.contains(part.toString().toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static boolean isIgnored(Path root, Path path) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        return normalizedPath.startsWith(normalizedRoot)
                && containsIgnoredDirectory(normalizedRoot, normalizedPath);
    }
}
