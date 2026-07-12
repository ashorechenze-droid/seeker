package com.simplerag.search;

import java.nio.file.Path;
import java.util.List;

/** Unified reader result used by all text-bearing formats. */
public record ReadDocument(Path path, Path root, String documentId, String extension,
                           List<DocumentSection> sections, long modifiedAt,
                           String readerId, int readerVersion, List<String> warnings) {
    public ReadDocument {
        path = path.toAbsolutePath().normalize();
        root = root.toAbsolutePath().normalize();
        documentId = documentId == null ? "" : documentId;
        extension = extension == null ? "" : extension;
        sections = sections == null ? List.of() : List.copyOf(sections);
        readerId = readerId == null ? "" : readerId;
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
