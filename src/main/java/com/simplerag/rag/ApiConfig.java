package com.simplerag.rag;

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
    }

    public void validateForChat() {
        validateForModels();
        if (model.isEmpty()) throw new IllegalArgumentException("请选择模型");
    }
}
