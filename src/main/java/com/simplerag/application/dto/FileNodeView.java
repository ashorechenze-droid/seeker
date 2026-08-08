package com.simplerag.application.dto;

import java.nio.file.Path;

/**
 * One row of the workspace file tree: what is on disk plus how the published index sees it.
 *
 * <p>Data only. The Swing layer owns the wording and colors so the desktop page can change its
 * presentation without touching the application layer.
 *
 * @param chunkCount        published chunks for this file, {@code 0} unless {@link FileIndexState#INDEXED}
 *                          or {@link FileIndexState#MODIFIED}
 * @param indexedDescendants indexed files below a directory, {@code 0} for files
 * @param contentHash       SHA-256 recorded by the snapshot, empty when the file is not indexed
 * @param root              whether this node is a knowledge-base source root
 */
public record FileNodeView(Path path, String name, boolean directory, FileIndexState state,
                           long size, long modifiedAt, String readerId, int chunkCount,
                           int indexedDescendants, String contentHash, boolean root) {
    public FileNodeView {
        name = name == null ? "" : name;
        readerId = readerId == null ? "" : readerId;
        contentHash = contentHash == null ? "" : contentHash;
    }

    /** True when the application can extract text for the in-app viewer. */
    public boolean previewable() {
        return !directory && (state == FileIndexState.INDEXED || state == FileIndexState.MODIFIED
                || state == FileIndexState.NOT_INDEXED);
    }
}
