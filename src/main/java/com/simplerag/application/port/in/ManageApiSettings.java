package com.simplerag.application.port.in;

import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;

public interface ManageApiSettings {
    ApiConfig apiConfig();
    void saveApiConfig(ApiConfig config);
    List<String> fetchModels(ApiConfig config) throws IOException, InterruptedException;
}
