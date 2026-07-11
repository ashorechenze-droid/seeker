package com.simplerag.application.port.out;

import com.simplerag.search.IndexManifest;

import java.util.Optional;

public interface IndexPublicationRepository {
    boolean beginIndexBuild(String knowledgeBaseId, long revision);
    void markIndexBuildFailed(String knowledgeBaseId, long revision, String error);
    void markIndexIncompatible(String knowledgeBaseId, String error);
    void markIndexDirty(String knowledgeBaseId, String error);
    boolean publishIndex(IndexManifest manifest, String fileName);
    Optional<String> findIndexFile(String knowledgeBaseId, long revision);
}
