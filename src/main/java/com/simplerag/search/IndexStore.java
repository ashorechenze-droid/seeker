package com.simplerag.search;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

public final class IndexStore {
    private final Path indexPath;

    public IndexStore() {
        this(Path.of(System.getProperty("user.home"), ".simplerag", "index.bin"));
    }

    public IndexStore(Path indexPath) {
        this.indexPath = indexPath;
    }

    public Optional<IndexSnapshot> load() {
        if (!Files.isRegularFile(indexPath)) {
            return Optional.empty();
        }
        try (ObjectInputStream input = new ObjectInputStream(Files.newInputStream(indexPath))) {
            Object value = input.readObject();
            if (value instanceof IndexSnapshot snapshot) {
                if (snapshot.version() == IndexSnapshot.CURRENT_VERSION) {
                    return Optional.of(snapshot);
                }
                if (snapshot.version() == 1) {
                    return Optional.of(new IndexSnapshot(IndexSnapshot.CURRENT_VERSION, snapshot.roots(),
                            snapshot.chunks(), snapshot.indexedAt(), ""));
                }
            }
        } catch (IOException | ClassNotFoundException ignored) {
            // A corrupt or old cache should never prevent the application from starting.
        }
        return Optional.empty();
    }

    public void save(IndexSnapshot snapshot) throws IOException {
        Files.createDirectories(indexPath.getParent());
        Path temporary = indexPath.resolveSibling(indexPath.getFileName() + ".tmp");
        try (ObjectOutputStream output = new ObjectOutputStream(Files.newOutputStream(temporary))) {
            output.writeObject(snapshot);
        }
        try {
            Files.move(temporary, indexPath, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicMoveUnsupported) {
            Files.move(temporary, indexPath, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public Path path() {
        return indexPath;
    }
}
