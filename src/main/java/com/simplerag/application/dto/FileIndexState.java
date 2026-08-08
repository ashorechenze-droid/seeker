package com.simplerag.application.dto;

/**
 * Per-file relationship between the published index snapshot and the file currently on disk.
 *
 * <p>Derived on demand by comparing {@code IndexSnapshot.documentEntries()} with the filesystem;
 * nothing here is persisted. Presentation labels and colors belong to the Swing layer.
 */
public enum FileIndexState {
    /** Present in the snapshot with a matching size and modification time. */
    INDEXED,
    /** Present in the snapshot, but the file on disk changed since it was indexed. */
    MODIFIED,
    /** Readable format that no published snapshot entry covers yet. */
    NOT_INDEXED,
    /** Present in the snapshot but no longer on disk. */
    DELETED,
    /** Beyond the size limit of the reader that would handle this format. */
    OVERSIZED,
    /** No registered reader handles this file name or extension. */
    UNSUPPORTED,
    /** Excluded by the traversal ignore list or the credential-file policy. */
    IGNORED,
    /** A directory; carries an indexed-descendant count instead of a per-file state. */
    FOLDER
}
