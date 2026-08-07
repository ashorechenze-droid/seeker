package com.simplerag.adapter.out.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.embedding.EmbeddingProvider;
import com.simplerag.rag.ModelApiConfig;
import com.simplerag.search.EmbeddingModelSignature;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/** Dynamically selects the local embedder or an OpenAI-compatible /embeddings API. */
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private final TextEmbedder local;
    private final Supplier<ModelApiConfig> config;
    private final HttpClient http;
    private final ObjectMapper json;
    private volatile String status = "";
    private volatile int observedDimension;

    public OpenAiCompatibleEmbeddingProvider(TextEmbedder local, Supplier<ModelApiConfig> config) {
        this(local, config, HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15)).build(), new ObjectMapper());
    }

    OpenAiCompatibleEmbeddingProvider(TextEmbedder local, Supplier<ModelApiConfig> config,
                                      HttpClient http, ObjectMapper json) {
        this.local = local;
        this.config = config;
        this.http = http;
        this.json = json;
    }

    @Override public boolean isConfigured() {
        ModelApiConfig current = config.get();
        if (!current.enabled()) return local.isConfigured();
        try { current.validate(); return true; }
        catch (RuntimeException invalid) { status = invalid.getMessage(); return false; }
    }

    @Override public List<float[]> embed(List<String> texts) throws IOException {
        if (texts == null || texts.isEmpty()) return List.of();
        ModelApiConfig current = config.get();
        if (!current.enabled()) return local.embed(texts);
        current.validate();
        ObjectNode payload = json.createObjectNode();
        payload.put("model", current.model());
        ArrayNode input = payload.putArray("input");
        texts.forEach(input::add);
        if (current.dimensions() > 0) payload.put("dimensions", current.dimensions());
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint(current.normalizedBaseUrl())))
                .timeout(TIMEOUT).header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (!current.apiKey().isBlank()) builder.header("Authorization", "Bearer " + current.apiKey());
        HttpResponse<String> response;
        try {
            response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("向量 API 请求已取消", interrupted);
        }
        JsonNode body = parseBody(response);
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw apiFailure(response, body);
        List<IndexedVector> indexed = new ArrayList<>();
        JsonNode data = body.path("data");
        if (!data.isArray()) throw new IOException("向量 API 响应缺少 data 数组");
        int fallbackIndex = 0;
        for (JsonNode item : data) {
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray() || embedding.isEmpty()) throw new IOException("向量 API 返回了空向量");
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) embedding.get(i).asDouble();
                if (!Float.isFinite(vector[i])) throw new IOException("向量 API 返回了无效数值");
            }
            indexed.add(new IndexedVector(item.path("index").asInt(fallbackIndex++), vector));
        }
        indexed.sort(Comparator.comparingInt(IndexedVector::index));
        if (indexed.size() != texts.size()) throw new IOException("向量 API 返回的向量数量不正确");
        int dimension = indexed.get(0).vector().length;
        if (indexed.stream().anyMatch(value -> value.vector().length != dimension)) {
            throw new IOException("向量 API 返回的向量维度不一致");
        }
        if (current.dimensions() > 0 && dimension != current.dimensions()) {
            throw new IOException("向量 API 返回维度 " + dimension + "，与设置的 " + current.dimensions() + " 不一致");
        }
        observedDimension = dimension;
        status = "远程向量已启用 · " + current.model();
        return indexed.stream().map(IndexedVector::vector).toList();
    }

    @Override public String modelName() {
        ModelApiConfig current = config.get();
        return current.enabled() ? current.model() : local.modelName();
    }

    @Override public String status() {
        ModelApiConfig current = config.get();
        if (!current.enabled()) return local.status();
        return status.isBlank() ? "远程向量 API 已配置" : status;
    }

    @Override public int dimension() {
        ModelApiConfig current = config.get();
        if (!current.enabled()) return local.dimension();
        return current.dimensions() > 0 ? current.dimensions() : observedDimension;
    }

    @Override public EmbeddingModelSignature signature() {
        ModelApiConfig current = config.get();
        if (!current.enabled()) return local.signature();
        String endpoint = current.normalizedBaseUrl().toLowerCase(java.util.Locale.ROOT);
        return new EmbeddingModelSignature("openai-compatible-api", current.model(), endpoint,
                current.dimensions(), 1);
    }

    @Override public void close() { local.close(); }

    private JsonNode parseBody(HttpResponse<String> response) throws IOException {
        try { return json.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body()); }
        catch (IOException invalid) { throw new IOException("向量 API 返回的不是有效 JSON（HTTP "
                + response.statusCode() + "）", invalid); }
    }

    private static IOException apiFailure(HttpResponse<String> response, JsonNode body) {
        String message = body.path("error").path("message").asText("");
        if (message.isBlank()) message = body.path("message").asText("HTTP " + response.statusCode());
        return new IOException("向量 API 请求失败：" + message);
    }

    private static String endpoint(String baseUrl) {
        if (baseUrl.endsWith("/embeddings")) return baseUrl;
        return baseUrl + "/embeddings";
    }

    private record IndexedVector(int index, float[] vector) { }
}
