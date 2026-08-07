package com.simplerag.application.port.in;

import com.simplerag.rag.ApiConfig;
import com.simplerag.rag.ModelApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public interface ManageApiSettings {
    ApiConfig apiConfig();
    void saveApiConfig(ApiConfig config);
    List<String> fetchModels(ApiConfig config) throws IOException, InterruptedException;
    default ModelApiConfig embeddingApiConfig() { return ModelApiConfig.disabled(); }
    default void saveEmbeddingApiConfig(ModelApiConfig config) { throw new UnsupportedOperationException("向量 API 设置不可用"); }
    default ModelApiConfig rerankApiConfig() { return ModelApiConfig.disabled(); }
    default void saveRerankApiConfig(ModelApiConfig config) { throw new UnsupportedOperationException("重排 API 设置不可用"); }
    boolean localOnly(String knowledgeBaseId);
    void saveLocalOnly(String knowledgeBaseId, boolean localOnly);
    Set<String> trustedHosts();
    void trustHost(String host);
}
