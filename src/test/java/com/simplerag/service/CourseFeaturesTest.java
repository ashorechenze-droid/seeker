package com.simplerag.service;

import com.simplerag.embedding.EmbeddingProvider;
import com.simplerag.model.KnowledgeBase;
import com.simplerag.model.RagAnswer;
import com.simplerag.rag.ApiConfig;
import com.simplerag.rag.OpenAiCompatibleClient;
import com.simplerag.repository.AppRepository;
import com.simplerag.repository.DatabaseManager;
import com.simplerag.repository.SecretCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class CourseFeaturesTest {
    public static void main(String[] args) throws Exception {
        Path work = Path.of("target", "course-features-test").toAbsolutePath();
        Files.createDirectories(work);
        Path databasePath = work.resolve("simplerag.db");
        Files.deleteIfExists(databasePath);

        DatabaseManager database = new DatabaseManager(databasePath);
        AppRepository repository = new AppRepository(database);
        KnowledgeBase first = repository.createKnowledgeBase("课程笔记", "软件工程资料");
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> chatRequest = new AtomicReference<>();
        HttpServer server = mockApi(authorization, chatRequest);
        server.start();

        KnowledgeService service = new KnowledgeService(new FakeEmbeddingProvider(), database,
                new SecretCodec(), new OpenAiCompatibleClient(), work.resolve("indexes"));
        service.restore();
        check(service.currentKnowledgeBase().id().equals(first.id()), "应选择第一个知识库");

        KnowledgeBase code = service.createKnowledgeBase("代码知识库", "代码与配置");
        service.addSource(Path.of("examples", "knowledge"));
        service.rebuildCurrent(null);
        check(service.roots().size() == 1, "代码知识库应保存独立数据源");
        check(service.stats().files() == 5, "代码知识库应索引示例文件");

        service.selectKnowledgeBase(first.id());
        check(service.roots().isEmpty(), "知识库之间的数据源必须隔离");
        service.selectKnowledgeBase(code.id());
        service.updateCurrentKnowledgeBase("代码与文档", "更新后的描述");
        check(service.currentKnowledgeBase().name().equals("代码与文档"), "应更新知识库名称");

        int port = server.getAddress().getPort();
        ApiConfig config = new ApiConfig("http://127.0.0.1:" + port + "/v1", "course-secret", "mock-model");
        service.saveApiConfig(config);
        check(service.apiConfig().apiKey().equals("course-secret"), "加密保存后应能读取 API Key");
        List<String> models = service.fetchModels(config);
        check(models.equals(List.of("mock-model", "second-model")), "应读取并排序模型列表");
        check("Bearer course-secret".equals(authorization.get()), "请求应携带 Bearer API Key");

        RagAnswer answer = service.ask("How should MySQL credentials be stored?", config);
        check(answer.text().contains("environment variables"), "应返回模拟 RAG 答案");
        check(!answer.citations().isEmpty(), "RAG 答案必须包含引用");
        check(chatRequest.get().contains("database-connection.md"), "发送给模型的上下文应包含来源文件");

        service.deleteKnowledgeBase(code.id());
        check(service.knowledgeBases().size() == 1, "删除后应保留另一个知识库");
        check(service.currentKnowledgeBase().id().equals(first.id()), "删除当前库后应自动切换");
        server.stop(0);
        System.out.println("CourseFeaturesTest: knowledge-base CRUD and RAG API checks passed");
    }

    private static HttpServer mockApi(AtomicReference<String> authorization,
                                      AtomicReference<String> chatRequest) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"data\":[{\"id\":\"second-model\"},{\"id\":\"mock-model\"}]}");
        });
        server.createContext("/v1/chat/completions", exchange -> {
            chatRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"Store credentials in environment variables [1].\"}}]}");
        });
        return server;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeEmbeddingProvider implements EmbeddingProvider {
        @Override public boolean isConfigured() { return true; }

        @Override public List<float[]> embed(List<String> texts) {
            List<float[]> result = new ArrayList<>();
            for (String text : texts) {
                float[] vector = new float[8];
                for (int i = 0; i < text.length(); i++) vector[Math.floorMod(text.charAt(i), vector.length)]++;
                float norm = 0;
                for (float value : vector) norm += value * value;
                norm = (float) Math.sqrt(norm);
                if (norm > 0) for (int i = 0; i < vector.length; i++) vector[i] /= norm;
                result.add(vector);
            }
            return result;
        }

        @Override public String modelName() { return "fake-test-model"; }
        @Override public String status() { return "test-ready"; }
        @Override public void close() { }
    }
}
