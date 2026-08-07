package com.simplerag.application.usecase;

import com.simplerag.common.text.TextValues;
import com.simplerag.application.port.in.ManageApiSettings;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.application.port.out.SecretStore;
import com.simplerag.application.port.out.SettingsRepository;
import com.simplerag.rag.ApiConfig;
import com.simplerag.rag.ModelApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class ApiSettingsUseCase implements ManageApiSettings {
    private static final String API_URL = "api.base_url";
    private static final String API_KEY = "api.encrypted_key";
    private static final String API_MODEL = "api.model";
    private static final String EMBEDDING_ENABLED = "embedding.api.enabled";
    private static final String EMBEDDING_URL = "embedding.api.base_url";
    private static final String EMBEDDING_KEY = "embedding.api.encrypted_key";
    private static final String EMBEDDING_MODEL = "embedding.api.model";
    private static final String EMBEDDING_DIMENSIONS = "embedding.api.dimensions";
    private static final String RERANK_ENABLED = "rerank.api.enabled";
    private static final String RERANK_URL = "rerank.api.base_url";
    private static final String RERANK_KEY = "rerank.api.encrypted_key";
    private static final String RERANK_MODEL = "rerank.api.model";
    private static final String TRUSTED_HOSTS = "api.trusted_hosts";
    private static final String LOCAL_ONLY_PREFIX = "rag.local_only.";
    private final SettingsRepository settings;
    private final SecretStore secrets;
    private final ChatModel chat;

    public ApiSettingsUseCase(SettingsRepository settings, SecretStore secrets, ChatModel chat) {
        this.settings = settings;
        this.secrets = secrets;
        this.chat = chat;
    }

    @Override public ApiConfig apiConfig() {
        String url = settings.getSetting(API_URL).orElse("http://localhost:11434/v1");
        String key = secrets.decrypt("chat", settings.getSetting(API_KEY).orElse(""));
        String model = settings.getSetting(API_MODEL).orElse("");
        return new ApiConfig(url, key, model);
    }

    @Override public void saveApiConfig(ApiConfig config) {
        config.validateForModels();
        settings.putSetting(API_URL, config.normalizedBaseUrl());
        settings.putSetting(API_KEY, secrets.encrypt("chat", config.apiKey()));
        settings.putSetting(API_MODEL, config.model());
    }
    @Override public List<String> fetchModels(ApiConfig config) throws IOException, InterruptedException {
        return chat.listModels(config);
    }

    @Override public ModelApiConfig embeddingApiConfig() {
        return modelConfig(EMBEDDING_ENABLED, EMBEDDING_URL, EMBEDDING_KEY, EMBEDDING_MODEL,
                EMBEDDING_DIMENSIONS, "embedding");
    }

    @Override public void saveEmbeddingApiConfig(ModelApiConfig config) {
        saveModelConfig(config, EMBEDDING_ENABLED, EMBEDDING_URL, EMBEDDING_KEY,
                EMBEDDING_MODEL, EMBEDDING_DIMENSIONS, "embedding");
    }

    @Override public ModelApiConfig rerankApiConfig() {
        return modelConfig(RERANK_ENABLED, RERANK_URL, RERANK_KEY, RERANK_MODEL,
                null, "rerank");
    }

    @Override public void saveRerankApiConfig(ModelApiConfig config) {
        saveModelConfig(config, RERANK_ENABLED, RERANK_URL, RERANK_KEY,
                RERANK_MODEL, null, "rerank");
    }

    private ModelApiConfig modelConfig(String enabledKey, String urlKey, String keyKey,
                                       String modelKey, String dimensionsKey, String namespace) {
        boolean enabled = Boolean.parseBoolean(settings.getSetting(enabledKey).orElse("false"));
        String url = settings.getSetting(urlKey).orElse("");
        String key = secrets.decrypt(namespace, settings.getSetting(keyKey).orElse(""));
        String model = settings.getSetting(modelKey).orElse("");
        int dimensions = dimensionsKey == null ? 0 : parseNonNegativeInt(
                settings.getSetting(dimensionsKey).orElse("0"));
        return new ModelApiConfig(enabled, url, key, model, dimensions);
    }

    private void saveModelConfig(ModelApiConfig config, String enabledKey, String urlKey,
                                 String keyKey, String modelKey, String dimensionsKey,
                                 String namespace) {
        config.validate();
        settings.putSetting(enabledKey, Boolean.toString(config.enabled()));
        settings.putSetting(urlKey, config.normalizedBaseUrl());
        settings.putSetting(keyKey, secrets.encrypt(namespace, config.apiKey()));
        settings.putSetting(modelKey, config.model());
        if (dimensionsKey != null) settings.putSetting(dimensionsKey, Integer.toString(config.dimensions()));
    }

    private static int parseNonNegativeInt(String value) {
        try { return Math.max(0, Integer.parseInt(value)); }
        catch (NumberFormatException invalid) { return 0; }
    }

    @Override public boolean localOnly(String knowledgeBaseId) {
        return Boolean.parseBoolean(settings.getSetting(LOCAL_ONLY_PREFIX + knowledgeBaseId).orElse("false"));
    }

    @Override public void saveLocalOnly(String knowledgeBaseId, boolean localOnly) {
        settings.putSetting(LOCAL_ONLY_PREFIX + knowledgeBaseId, Boolean.toString(localOnly));
    }

    @Override public Set<String> trustedHosts() {
        return Arrays.stream(settings.getSetting(TRUSTED_HOSTS).orElse("").split(","))
                .map(String::strip).filter(value -> !value.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override public void trustHost(String host) {
        java.util.TreeSet<String> hosts = new java.util.TreeSet<>(trustedHosts());
        String normalized = TextValues.normalizedKey(host);
        if (!normalized.isEmpty()) hosts.add(normalized);
        settings.putSetting(TRUSTED_HOSTS, String.join(",", hosts));
    }
}
