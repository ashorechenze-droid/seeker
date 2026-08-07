package com.simplerag.rag;

import com.simplerag.common.text.TextValues;

import java.net.URI;

/** Configuration shared by OpenAI-compatible embedding and reranking endpoints. */
public record ModelApiConfig(boolean enabled, String baseUrl, String apiKey, String model, int dimensions) {
    public ModelApiConfig {
        baseUrl = TextValues.trimToEmpty(baseUrl);
        apiKey = TextValues.trimToEmpty(apiKey);
        model = TextValues.trimToEmpty(model);
        if (dimensions < 0) throw new IllegalArgumentException("向量维度不能小于 0");
    }

    public static ModelApiConfig disabled() {
        return new ModelApiConfig(false, "", "", "", 0);
    }

    public String normalizedBaseUrl() {
        String result = baseUrl;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    public void validate() {
        if (!enabled) return;
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
        if (model.isEmpty()) throw new IllegalArgumentException("请填写模型名称");
    }

    public ApiConfig asApiConfig() {
        return new ApiConfig(baseUrl, apiKey, model);
    }
}
