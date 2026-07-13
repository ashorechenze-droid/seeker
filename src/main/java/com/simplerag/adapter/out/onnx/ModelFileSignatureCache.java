package com.simplerag.adapter.out.onnx;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.simplerag.common.crypto.Digests;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistent SHA-256 cache keyed by canonical path, size, mtime and filesystem identity. */
public final class ModelFileSignatureCache {
    private final Path cacheFile;
    private final ObjectMapper json = new ObjectMapper();

    public ModelFileSignatureCache(Path cacheFile) {
        this.cacheFile = cacheFile.toAbsolutePath().normalize();
    }

    public synchronized String signature(List<Path> files) throws IOException {
        Map<String, Entry> cache = readCache();
        boolean changed = false;
        MessageDigest combined = Digests.sha256();
        for (Path file : files) {
            if (!Files.isRegularFile(file)) continue;
            Path canonical = file.toRealPath();
            BasicFileAttributes attributes = Files.readAttributes(canonical, BasicFileAttributes.class);
            String key = canonical.toString();
            String fileKey = String.valueOf(attributes.fileKey());
            Entry entry = cache.get(key);
            if (entry == null || entry.size() != attributes.size()
                    || entry.modifiedAt() != attributes.lastModifiedTime().toMillis()
                    || !entry.fileKey().equals(fileKey)) {
                entry = new Entry(attributes.size(), attributes.lastModifiedTime().toMillis(), fileKey,
                        hashFile(canonical));
                cache.put(key, entry);
                changed = true;
            }
            combined.update(entry.sha256().getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        if (changed) writeCache(cache);
        return Digests.hex(combined.digest());
    }

    private Map<String, Entry> readCache() {
        if (!Files.isRegularFile(cacheFile)) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(json.readValue(cacheFile.toFile(), new TypeReference<Map<String, Entry>>() { }));
        } catch (IOException | RuntimeException ignored) {
            return new LinkedHashMap<>();
        }
    }

    private void writeCache(Map<String, Entry> cache) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Path temporary = Files.createTempFile(cacheFile.getParent(), "model-signatures-", ".tmp");
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), cache);
            try {
                Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static String hashFile(Path file) throws IOException {
        return Digests.sha256File(file);
    }

    public record Entry(long size, long modifiedAt, String fileKey, String sha256) { }
}
