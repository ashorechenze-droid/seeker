package com.simplerag.rag;

import com.simplerag.common.text.TextValues;

import java.net.URI;

public record ApiConfig(String baseUrl, String apiKey, String model) {
    public ApiConfig {
        baseUrl = TextValues.trimToEmpty(baseUrl);
        apiKey = TextValues.trimToEmpty(apiKey);
        model = TextValues.trimToEmpty(model);
    }

    public String normalizedBaseUrl() {
        String result = baseUrl;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public void validateForModels() {
        if (normalizedBaseUrl().isEmpty()) throw new IllegalArgumentException("请填写 API URL");
        URI uri;
        try {
            uri = URI.create(normalizedBaseUrl());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("API URL 格式无效");
        }
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("API URL 必须是无内嵌凭据的 HTTP(S) 地址");
        }
    }

    public String targetHost() {
        validateForModels();
        URI uri = URI.create(normalizedBaseUrl());
        int port = uri.getPort();
        String host = TextValues.normalizedKey(uri.getHost());
        return port < 0 ? host : host + ":" + port;
    }

    public void validateForChat() {
        validateForModels();
        if (model.isEmpty()) throw new IllegalArgumentException("请选择模型");
    }
}
