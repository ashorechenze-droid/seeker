package com.simplerag.search;

import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class IndexStore {
    /**
     * Deserialization allowlist for index snapshots.
     *
     * <p>Snapshot files live under the user profile, so a replaced or planted {@code .bin} would
     * otherwise be handed to an unrestricted {@code readObject}. The trailing {@code !*} rejects every
     * class outside the snapshot's own object graph, which is what blocks third-party gadget chains
     * (POI, PDFBox, commons-*) from ever being instantiated. The depth, reference and array bounds cap
     * resource exhaustion from a hand-crafted stream.
     */
    private static final String ALLOWED_CLASSES =
            "maxdepth=64;maxrefs=20000000;maxarray=20000000;"
                    + "com.simplerag.search.*;com.simplerag.model.*;"
                    + "java.util.*;java.lang.*;"
                    + "[F;[B;[J;[I;[Ljava.lang.Object;[Ljava.lang.String;"
                    + "!*";

    private final Path indexPath;
    private final boolean versioned;

    public IndexStore() {
        this(Path.of(System.getProperty("user.home"), ".simplerag", "index.bin"));
    }

    public IndexStore(Path indexPath) {
        this(indexPath, false);
    }

    private IndexStore(Path indexPath, boolean versioned) {
        this.indexPath = indexPath.toAbsolutePath().normalize();
        this.versioned = versioned;
    }

    public static IndexStore versioned(Path indexesDirectory, String knowledgeBaseId) {
        return new IndexStore(indexesDirectory.resolve(knowledgeBaseId), true);
    }

    public Optional<IndexSnapshot> load() {
        return loadPath(indexPath);
    }

    public Optional<IndexSnapshot> loadRevision(long revision) {
        return loadPath(revisionPath(revision));
    }

    private Optional<IndexSnapshot> loadPath(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        ObjectInputFilter filter;
        try {
            // Built outside the read so a malformed pattern fails loudly instead of silently
            // disabling every index load.
            filter = snapshotFilter(Files.size(path));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(path))) {
            input.setObjectInputFilter(filter);
            Object value = input.readObject();
            if (value instanceof IndexSnapshot snapshot) {
                if (snapshot.version() == IndexSnapshot.CURRENT_VERSION) {
                    return Optional.of(snapshot);
                }
                if (snapshot.version() >= 1 && snapshot.version() < IndexSnapshot.CURRENT_VERSION) {
                    return Optional.of(new IndexSnapshot(IndexSnapshot.CURRENT_VERSION, snapshot.roots(),
                            snapshot.chunks(), snapshot.indexedAt(), "", null));
                }
            }
        } catch (IOException | ClassNotFoundException | RuntimeException ignored) {
            // A corrupt, rejected or old cache should never prevent the application from starting.
            // Returning empty makes the caller treat the index as missing and require a rebuild.
        }
        return Optional.empty();
    }

    private static ObjectInputFilter snapshotFilter(long maxBytes) {
        return ObjectInputFilter.Config.createFilter("maxbytes=" + Math.max(1, maxBytes) + ";" + ALLOWED_CLASSES);
    }

    public void save(IndexSnapshot snapshot) throws IOException {
        savePath(snapshot, indexPath);
    }

    public String saveRevision(IndexSnapshot snapshot) throws IOException {
        if (!versioned || snapshot.manifest() == null) {
            throw new IllegalStateException("版本化索引必须包含 manifest");
        }
        Path target = revisionPath(snapshot.manifest().sourceRevision());
        savePath(snapshot, target);
        return target.getFileName().toString();
    }

    private void savePath(IndexSnapshot snapshot, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(temporary))) {
                output.writeObject(snapshot);
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            throw new IOException("当前文件系统不支持原子索引发布", unsupported);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public void deleteRevision(long revision) throws IOException {
        Files.deleteIfExists(revisionPath(revision));
        Files.deleteIfExists(revisionPath(revision).resolveSibling(revision + ".bin.tmp"));
    }

    public void cleanTemporaryFiles() throws IOException {
        if (!Files.isDirectory(indexPath)) return;
        try (var files = Files.list(indexPath)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".tmp")).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    public void deleteAll() throws IOException {
        if (!versioned || !Files.exists(indexPath)) return;
        try (var paths = Files.walk(indexPath)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private Path revisionPath(long revision) {
        if (!versioned) return indexPath;
        return indexPath.resolve(revision + ".bin");
    }

    public Path path() {
        return indexPath;
    }
}
