package com.simplerag.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure policy: compares published file entries with current content fingerprints. */
public final class IncrementalIndexPlanner {
    public IncrementalIndexPlan plan(List<DocumentIndexEntry> previousEntries,
                                     List<FileFingerprint> currentFingerprints,
                                     int readerVersion, int chunkingVersion,
                                     boolean reuseCompatible) {
        return plan(previousEntries, currentFingerprints.stream()
                .map(fingerprint -> new IndexableDocument(fingerprint, "plain-text", readerVersion)).toList(),
                chunkingVersion, reuseCompatible);
    }

    public IncrementalIndexPlan plan(List<DocumentIndexEntry> previousEntries,
                                     List<IndexableDocument> currentDocuments,
                                     int chunkingVersion, boolean reuseCompatible) {
        Map<String, DocumentIndexEntry> remaining = new LinkedHashMap<>();
        previousEntries.forEach(entry -> remaining.put(entry.key(), entry));
        List<FileFingerprint> added = new ArrayList<>();
        List<FileFingerprint> modified = new ArrayList<>();
        List<DocumentIndexEntry> reused = new ArrayList<>();

        for (IndexableDocument currentDocument : currentDocuments) {
            FileFingerprint current = currentDocument.fingerprint();
            DocumentIndexEntry previous = remaining.remove(currentDocument.key());
            if (previous == null) {
                added.add(current);
            } else if (reuseCompatible
                    && previous.readerId().equals(currentDocument.readerId())
                    && previous.readerVersion() == currentDocument.readerVersion()
                    && previous.chunkingVersion() == chunkingVersion
                    && previous.fingerprint().equals(current)) {
                reused.add(previous);
            } else {
                modified.add(current);
            }
        }
        return new IncrementalIndexPlan(added, modified, new ArrayList<>(remaining.values()), reused);
    }
}
