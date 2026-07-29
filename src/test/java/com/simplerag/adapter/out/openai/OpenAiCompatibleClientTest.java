package com.simplerag.adapter.out.openai;

import com.simplerag.application.conversation.RetrievalAttempt;
import com.simplerag.application.conversation.RetrievalDecision;
import com.simplerag.application.conversation.RetrievalPlanRequest;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.RagCitation;
import com.simplerag.rag.ApiConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleClientTest {
    @Test
    void plannerParsesJsonSearchDecisionAndSendsCurrentEvidence() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "{\"choices\":[{\"message\":{\"content\":\"```json\\n{\\\"action\\\":\\\"search\\\",\\\"query\\\":\\\"AskUseCase call chain\\\"}\\n```\"}}]}");
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            ApiConfig config = new ApiConfig("http://127.0.0.1:" + port + "/v1", "secret", "model");
            DocumentChunk chunk = new DocumentChunk("c1", "src/AskUseCase.java", "src", "AskUseCase.java",
                    ".java", 60, 90, "evidence", 1L, null);
            RetrievalPlanRequest request = new RetrievalPlanRequest("kb", 2L, "where is retrieval?", List.of(),
                    List.of(new RagCitation(1, chunk, 0.9)),
                    List.of(new RetrievalAttempt("where is retrieval?", 1)), 3);

            RetrievalDecision decision = new OpenAiCompatibleClient().planRetrieval(config, request);

            assertTrue(decision.shouldSearch());
            assertEquals("AskUseCase call chain", decision.query());
            assertTrue(requestBody.get().contains("src/AskUseCase.java"));
            assertTrue(requestBody.get().contains("Previous searches"));
            assertTrue(requestBody.get().contains("\"stream\":false"));
            assertFalse(requestBody.get().contains("secret"));
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
