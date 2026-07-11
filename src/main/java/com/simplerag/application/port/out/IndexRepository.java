package com.simplerag.application.port.out;

import com.simplerag.search.IndexSnapshot;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public interface IndexRepository {
    Optional<IndexSnapshot> loadRevision(String knowledgeBaseId, long revision);
    Optional<IndexSnapshot> loadLegacy(String knowledgeBaseId);
    Optional<IndexSnapshot> loadGlobalLegacy();
    String saveRevision(String knowledgeBaseId, IndexSnapshot snapshot) throws IOException;
    void deleteRevision(String knowledgeBaseId, long revision) throws IOException;
    void cleanTemporaryFiles(String knowledgeBaseId) throws IOException;
    void cleanUnreferenced(String knowledgeBaseId, Long publishedRevision) throws IOException;
    void deleteIndex(String knowledgeBaseId) throws IOException;
    Path location(String knowledgeBaseId);
    boolean usesDefaultLocation();
}
