package com.simplerag.application.port.out;

import java.nio.file.Path;
import java.util.List;

public interface KnowledgeSourceRepository {
    List<Path> listSources(String knowledgeBaseId);
    void addSource(String knowledgeBaseId, Path path);
    void removeSource(String knowledgeBaseId, Path path);
}
