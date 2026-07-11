package com.simplerag.adapter.out.filesystem;

import com.simplerag.application.port.out.IndexRepository;
import com.simplerag.search.IndexSnapshot;
import com.simplerag.search.IndexStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public final class FileSystemIndexRepository implements IndexRepository {
    private final Path indexesDirectory;
    private final Path defaultIndexesDirectory;

    public FileSystemIndexRepository(Path indexesDirectory) {
        this.indexesDirectory = indexesDirectory.toAbsolutePath().normalize();
        this.defaultIndexesDirectory = Path.of(System.getProperty("user.home"), ".simplerag", "indexes")
                .toAbsolutePath().normalize();
    }

    @Override public Optional<IndexSnapshot> loadRevision(String id, long revision) {
        return IndexStore.versioned(indexesDirectory, id).loadRevision(revision);
    }

    @Override public Optional<IndexSnapshot> loadLegacy(String id) {
        return new IndexStore(indexesDirectory.resolve(id + ".bin")).load();
    }

    @Override public Optional<IndexSnapshot> loadGlobalLegacy() {
        return new IndexStore().load();
    }

    @Override public String saveRevision(String id, IndexSnapshot snapshot) throws IOException {
        return IndexStore.versioned(indexesDirectory, id).saveRevision(snapshot);
    }

    @Override public void deleteRevision(String id, long revision) throws IOException {
        IndexStore.versioned(indexesDirectory, id).deleteRevision(revision);
    }

    @Override public void cleanTemporaryFiles(String id) throws IOException {
        IndexStore.versioned(indexesDirectory, id).cleanTemporaryFiles();
    }

    @Override
    public void cleanUnreferenced(String id, Long publishedRevision) throws IOException {
        Path directory = indexesDirectory.resolve(id);
        if (!java.nio.file.Files.isDirectory(directory)) return;
        try (var files = java.nio.file.Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".bin")) continue;
                String expected = publishedRevision == null ? "" : publishedRevision + ".bin";
                if (!name.equals(expected)) java.nio.file.Files.deleteIfExists(file);
            }
        }
    }

    @Override public void deleteIndex(String id) throws IOException {
        java.nio.file.Files.deleteIfExists(indexesDirectory.resolve(id + ".bin"));
        IndexStore.versioned(indexesDirectory, id).deleteAll();
    }

    @Override public Path location(String id) {
        return indexesDirectory.resolve(id);
    }

    @Override public boolean usesDefaultLocation() {
        return indexesDirectory.equals(defaultIndexesDirectory);
    }
}
