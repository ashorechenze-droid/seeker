package com.simplerag.application.port.out;

import com.simplerag.model.KnowledgeBase;
import com.simplerag.search.IndexManifest;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface KnowledgeBaseRepository {
    List<KnowledgeBase> listKnowledgeBases();
    Optional<KnowledgeBase> findKnowledgeBase(String id);
    KnowledgeBase createKnowledgeBase(String name, String description);
    KnowledgeBase updateKnowledgeBase(String id, String name, String description);
    void deleteKnowledgeBase(String id);
    List<Path> listSources(String knowledgeBaseId);
    void addSource(String knowledgeBaseId, Path path);
    void removeSource(String knowledgeBaseId, Path path);
    boolean beginIndexBuild(String knowledgeBaseId, long revision);
    void markIndexBuildFailed(String knowledgeBaseId, long revision, String error);
    void markIndexIncompatible(String knowledgeBaseId, String error);
    void markIndexDirty(String knowledgeBaseId, String error);
    boolean markIndexDirtyIfCurrent(String knowledgeBaseId, long sourceRevision, String reason,
                                    String observedSourceHash, Long verifiedAt);
    void recordSourceVerification(String knowledgeBaseId, long sourceRevision, String sourceHash, long verifiedAt);
    boolean publishIndex(IndexManifest manifest, String fileName);
    Optional<String> findIndexFile(String knowledgeBaseId, long revision);
    Path databasePath();
}
