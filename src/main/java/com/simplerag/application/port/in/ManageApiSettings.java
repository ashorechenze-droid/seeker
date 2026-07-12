package com.simplerag.application.port.in;

import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public interface ManageApiSettings {
    ApiConfig apiConfig();
    void saveApiConfig(ApiConfig config);
    List<String> fetchModels(ApiConfig config) throws IOException, InterruptedException;
    boolean localOnly(String knowledgeBaseId);
    void saveLocalOnly(String knowledgeBaseId, boolean localOnly);
    Set<String> trustedHosts();
    void trustHost(String host);
}
