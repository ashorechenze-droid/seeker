package com.simplerag.application.usecase;

import com.simplerag.application.port.in.ManageApiSettings;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.application.port.out.SecretStore;
import com.simplerag.application.port.out.SettingsRepository;
import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class ApiSettingsUseCase implements ManageApiSettings {
    private static final String API_URL = "api.base_url";
    private static final String API_KEY = "api.encrypted_key";
    private static final String API_MODEL = "api.model";
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
        String key = secrets.decrypt(settings.getSetting(API_KEY).orElse(""));
        String model = settings.getSetting(API_MODEL).orElse("");
        return new ApiConfig(url, key, model);
    }

    @Override public void saveApiConfig(ApiConfig config) {
        config.validateForModels();
        settings.putSetting(API_URL, config.normalizedBaseUrl());
        settings.putSetting(API_KEY, secrets.encrypt(config.apiKey()));
        settings.putSetting(API_MODEL, config.model());
    }
    @Override public List<String> fetchModels(ApiConfig config) throws IOException, InterruptedException {
        return chat.listModels(config);
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
        if (host != null && !host.isBlank()) hosts.add(host.strip().toLowerCase(java.util.Locale.ROOT));
        settings.putSetting(TRUSTED_HOSTS, String.join(",", hosts));
    }
}
