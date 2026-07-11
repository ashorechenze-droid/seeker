package com.simplerag.application.port.out;

import com.simplerag.model.KnowledgeBase;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Compatibility aggregate; application use cases consume the smaller parent ports. */
public interface KnowledgeBaseRepository extends KnowledgeSourceRepository,
        IndexPublicationRepository, FreshnessRepository {
    List<KnowledgeBase> listKnowledgeBases();
    Optional<KnowledgeBase> findKnowledgeBase(String id);
    KnowledgeBase createKnowledgeBase(String name, String description);
    KnowledgeBase updateKnowledgeBase(String id, String name, String description);
    void deleteKnowledgeBase(String id);
    Path databasePath();
}
