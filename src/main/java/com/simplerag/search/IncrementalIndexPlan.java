package com.simplerag.search;

import java.util.List;

/** Immutable output of the pure incremental planning policy. */
public record IncrementalIndexPlan(
        List<FileFingerprint> added,
        List<FileFingerprint> modified,
        List<DocumentIndexEntry> deleted,
        List<DocumentIndexEntry> reused
) {
    public IncrementalIndexPlan {
        added = List.copyOf(added);
        modified = List.copyOf(modified);
        deleted = List.copyOf(deleted);
        reused = List.copyOf(reused);
    }
}
