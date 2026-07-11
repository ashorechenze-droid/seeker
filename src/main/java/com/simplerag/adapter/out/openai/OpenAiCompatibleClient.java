package com.simplerag.adapter.out.openai;

import com.simplerag.rag.ApiConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

public final class OpenAiCompatibleClient implements com.simplerag.application.port.out.ChatModel {
    private final HttpClient httpClient;
    private final ObjectMapper json;

    public OpenAiCompatibleClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(), new ObjectMapper());
    }

    OpenAiCompatibleClient(HttpClient httpClient, ObjectMapper json) {
        this.httpClient = httpClient;
        this.json = json;
    }

    public List<String> listModels(ApiConfig config) throws IOException, InterruptedException {
        config.validateForModels();
        HttpRequest request = request(config, endpoint(config.normalizedBaseUrl(), "models"))
                .GET().build();
        JsonNode response = send(request);
        JsonNode data = response.path("data");
        List<String> models = new ArrayList<>();
        if (data.isArray()) {
            for (JsonNode item : data) {
                String id = item.path("id").asText("").strip();
                if (!id.isEmpty()) models.add(id);
            }
        } else if (response.path("models").isArray()) {
            for (JsonNode item : response.path("models")) {
                String id = item.has("name") ? item.path("name").asText("") : item.path("id").asText("");
                if (!id.isBlank()) models.add(id.strip());
            }
        }
        models.sort(Comparator.naturalOrder());
        return List.copyOf(models);
    }

    public RagAnswer answer(ApiConfig config, String question, List<RagCitation> citations)
            throws IOException, InterruptedException {
        validateAnswerInput(config, question, citations);
        ObjectNode payload = chatPayload(config, question, citations, false);
        HttpRequest request = request(config, endpoint(config.normalizedBaseUrl(), "chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        JsonNode response = send(request);
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        String answer = content.isTextual() ? content.asText() : flattenContent(content);
        if (answer.isBlank()) throw new IOException("API 返回了空答案");
        return new RagAnswer(answer.strip(), List.copyOf(citations), config.model());
    }

    /**
     * Streams the answer token by token via server-sent events, invoking {@code onDelta} for each
     * incremental chunk of text. Falls back to the non-streaming {@link #answer} call when the server
     * does not honour SSE, so callers always receive a complete {@link RagAnswer}.
     */
    public RagAnswer answerStream(ApiConfig config, String question, List<RagCitation> citations,
                                  Consumer<String> onDelta) throws IOException, InterruptedException {
        validateAnswerInput(config, question, citations);
        ObjectNode payload = chatPayload(config, question, citations, true);
        HttpRequest request = request(config, endpoint(config.normalizedBaseUrl(), "chat/completions"))
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            return answer(config, question, citations);
        }
        StringBuilder full = new StringBuilder();
        boolean streamed = false;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("已取消问答");
                }
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).strip();
                if (data.isEmpty()) continue;
                if ("[DONE]".equals(data)) break;
                streamed = true;
                String delta = extractDelta(data);
                if (!delta.isEmpty()) {
                    full.append(delta);
                    if (onDelta != null) onDelta.accept(delta);
                }
            }
        }
        if (!streamed) {
            // The endpoint accepted the request but did not emit SSE frames; retry without streaming.
            return answer(config, question, citations);
        }
        if (full.toString().isBlank()) throw new IOException("API 返回了空答案");
        return new RagAnswer(full.toString().strip(), List.copyOf(citations), config.model());
    }

    private void validateAnswerInput(ApiConfig config, String question, List<RagCitation> citations) {
        config.validateForChat();
        if (question == null || question.isBlank()) throw new IllegalArgumentException("请输入问题");
        if (citations.isEmpty()) throw new IllegalArgumentException("当前知识库没有可用于回答的相关内容");
    }

    private ObjectNode chatPayload(ApiConfig config, String question, List<RagCitation> citations, boolean stream) {
        ObjectNode payload = json.createObjectNode();
        payload.put("model", config.model());
        payload.put("temperature", 0.2);
        payload.put("stream", stream);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt());
        messages.addObject().put("role", "user").put("content", userPrompt(question, citations));
        return payload;
    }

    private String extractDelta(String data) {
        try {
            JsonNode node = json.readTree(data);
            JsonNode choice = node.path("choices").path(0);
            JsonNode content = choice.path("delta").path("content");
            if (content.isMissingNode() || content.isNull()) {
                content = choice.path("message").path("content");
            }
            return content.isTextual() ? content.asText() : "";
        } catch (IOException malformedFrame) {
            // A partial SSE frame can arrive split across reads; skipping it keeps the stream alive.
            return "";
        }
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode body;
        try {
            body = json.readTree(response.body().isBlank() ? "{}" : response.body());
        } catch (IOException invalidJson) {
            throw new IOException("API 返回的不是有效 JSON（HTTP " + response.statusCode() + "）", invalidJson);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = body.path("error").path("message").asText("");
            if (message.isBlank()) message = body.path("message").asText("HTTP " + response.statusCode());
            throw new IOException("API 请求失败：" + message);
        }
        return body;
    }

    private HttpRequest.Builder request(ApiConfig config, String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(90))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (!config.apiKey().isBlank()) builder.header("Authorization", "Bearer " + config.apiKey());
        return builder;
    }

    private static String endpoint(String baseUrl, String resource) {
        String base = baseUrl;
        if (base.endsWith("/models")) base = base.substring(0, base.length() - "/models".length());
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        return base + "/" + resource;
    }

    private static String systemPrompt() {
        return """
                你是一个本地知识库问答助手。只能根据用户提供的检索资料回答。
                每个事实后使用 [1]、[2] 形式标注来源编号；编号必须来自资料标题。
                如果资料不足以回答，明确说明“当前知识库中没有足够信息”，不要编造。
                保留代码、命令、路径和配置名称的原始拼写。回答使用用户提问的语言。
                """.strip();
    }

    private static String userPrompt(String question, List<RagCitation> citations) {
        StringBuilder prompt = new StringBuilder("问题：").append(question.strip()).append("\n\n检索资料：\n");
        int budget = 14_000;
        for (RagCitation citation : citations) {
            String block = "\n[" + citation.number() + "] 文件：" + citation.chunk().path()
                    + "，行 " + citation.chunk().startLine() + "-" + citation.chunk().endLine()
                    + "\n" + citation.chunk().content() + "\n";
            if (prompt.length() + block.length() > budget) break;
            prompt.append(block);
        }
        prompt.append("\n请基于以上资料回答，并在相关陈述后标注引用编号。");
        return prompt.toString();
    }

    private static String flattenContent(JsonNode content) {
        if (!content.isArray()) return content.asText("");
        StringBuilder result = new StringBuilder();
        for (JsonNode part : content) {
            String text = part.path("text").asText("");
            if (!text.isBlank()) result.append(text);
        }
        return result.toString();
    }
}
