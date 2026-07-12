package com.simplerag.rag;

import java.net.URI;

public record ApiConfig(String baseUrl, String apiKey, String model) {
    public ApiConfig {
        baseUrl = baseUrl == null ? "" : baseUrl.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null ? "" : model.strip();
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
        return port < 0 ? uri.getHost().toLowerCase(java.util.Locale.ROOT)
                : uri.getHost().toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    public void validateForChat() {
        validateForModels();
        if (model.isEmpty()) throw new IllegalArgumentException("请选择模型");
    }
}
