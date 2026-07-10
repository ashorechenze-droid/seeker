package com.simplerag.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplerag.model.RagAnswer;
import com.simplerag.model.RagCitation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class OpenAiCompatibleClient {
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
        config.validateForChat();
        if (question == null || question.isBlank()) throw new IllegalArgumentException("请输入问题");
        if (citations.isEmpty()) throw new IllegalArgumentException("当前知识库没有可用于回答的相关内容");

        ObjectNode payload = json.createObjectNode();
        payload.put("model", config.model());
        payload.put("temperature", 0.2);
        payload.put("stream", false);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt());
        messages.addObject().put("role", "user").put("content", userPrompt(question, citations));

        HttpRequest request = request(config, endpoint(config.normalizedBaseUrl(), "chat/completions"))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
        JsonNode response = send(request);
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        String answer = content.isTextual() ? content.asText() : flattenContent(content);
        if (answer.isBlank()) throw new IOException("API 返回了空答案");
        return new RagAnswer(answer.strip(), List.copyOf(citations), config.model());
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
