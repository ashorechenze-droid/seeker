package com.simplerag.application.usecase;

import com.simplerag.application.port.in.ManageApiSettings;
import com.simplerag.application.port.out.ChatModel;
import com.simplerag.application.port.out.SecretStore;
import com.simplerag.application.port.out.SettingsRepository;
import com.simplerag.rag.ApiConfig;

import java.io.IOException;
import java.util.List;

public final class ApiSettingsUseCase implements ManageApiSettings {
    private static final String API_URL = "api.base_url";
    private static final String API_KEY = "api.encrypted_key";
    private static final String API_MODEL = "api.model";
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
}
