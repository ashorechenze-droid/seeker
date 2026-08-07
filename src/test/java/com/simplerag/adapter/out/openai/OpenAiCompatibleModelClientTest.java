package com.simplerag.adapter.out.openai;

import com.simplerag.application.diagnostics.DiagnosticSink;
import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.rag.ModelApiConfig;
import com.simplerag.search.RetrievalCandidate;
import com.simplerag.search.RetrievalDocument;
import com.simplerag.search.QueryAnalyzer;
import com.simplerag.model.DocumentChunk;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAiCompatibleModelClientTest {
    @Test
    void embeddingApiParsesIndexedBatchAndSendsDimensions() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = server("/v1/embeddings", exchange -> {
            request.set(body(exchange));
            respond(exchange, "{\"data\":[{\"index\":1,\"embedding\":[0.3,0.4]},"
                    + "{\"index\":0,\"embedding\":[0.1,0.2]}]}");
        });
        server.start();
        try {
            ModelApiConfig config = new ModelApiConfig(true, base(server, "/v1"), "secret", "embed-v1", 2);
            TextEmbedder local = new StubEmbedder();
            OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(local, () -> config);

            List<float[]> vectors = provider.embed(List.of("first", "second"));

            assertEquals(2, vectors.size());
            assertEquals(0.1f, vectors.get(0)[0], 0.0001f);
            assertEquals(0.4f, vectors.get(1)[1], 0.0001f);
            assertTrue(request.get().contains("\"model\":\"embed-v1\""));
            assertTrue(request.get().contains("\"dimensions\":2"));
            assertEquals("embed-v1", provider.modelName());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void embeddingApiRejectsUnexpectedDimension() throws Exception {
        HttpServer server = server("/v1/embeddings", exchange ->
                respond(exchange, "{\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]}]}"));
        server.start();
        try {
            ModelApiConfig config = new ModelApiConfig(true, base(server, "/v1"), "", "embed-v1", 2);
            OpenAiCompatibleEmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                    new StubEmbedder(), () -> config);
            assertThrows(IOException.class, () -> provider.embed(List.of("text")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rerankApiUsesReturnedOrderAndFallsBackWhenUnavailable() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        HttpServer server = server("/v1/rerank", exchange -> {
            request.set(body(exchange));
            respond(exchange, "{\"results\":[{\"index\":1,\"relevance_score\":0.95},"
                    + "{\"index\":0,\"relevance_score\":0.2}]}");
        });
        server.start();
        try {
            List<RetrievalCandidate> candidates = candidates();
            ModelApiConfig config = new ModelApiConfig(true, base(server, "/v1"), "", "rerank-v1", 0);
            OpenAiCompatibleReranker reranker = new OpenAiCompatibleReranker(
                    new com.simplerag.search.FeatureReranker(), () -> config, DiagnosticSink.noop());
            QueryAnalyzer.AnalyzedQuery query = new QueryAnalyzer(new com.simplerag.search.LexicalFeatureExtractor())
                    .analyze("second", Map.of(), 2);

            List<RetrievalCandidate> ranked = reranker.rerank(query, candidates, 2);

            assertEquals("second.md", ranked.get(0).document().chunk().fileName());
            assertEquals(0.95, ranked.get(0).finalScore(), 0.0001);
            assertTrue(request.get().contains("\"query\":\"second\""));
            assertTrue(request.get().contains("\"model\":\"rerank-v1\""));
        } finally {
            server.stop(0);
        }

        ModelApiConfig unavailable = new ModelApiConfig(true, "http://127.0.0.1:1/v1", "", "rerank-v1", 0);
        OpenAiCompatibleReranker fallback = new OpenAiCompatibleReranker(
                new com.simplerag.search.FeatureReranker(), () -> unavailable, DiagnosticSink.noop());
        List<RetrievalCandidate> fallbackResults = fallback.rerank(
                new QueryAnalyzer(new com.simplerag.search.LexicalFeatureExtractor()).analyze("first", Map.of(), 2),
                candidates(), 2);
        assertEquals(2, fallbackResults.size());
        assertTrue(fallback.name().contains("local-feature-reranker"));
    }

    private static List<RetrievalCandidate> candidates() {
        return List.of(candidate("first.md", "first content"), candidate("second.md", "second content"));
    }

    private static RetrievalCandidate candidate(String file, String content) {
        DocumentChunk chunk = new DocumentChunk(file, file, ".", file, ".md", 1, 2, content, 1L, null);
        RetrievalDocument document = new RetrievalDocument(chunk, Map.of(), Map.of(), 0, 1);
        return new RetrievalCandidate(document, 0.5, 0, 0.5, 0.2, 0.2);
    }

    private static HttpServer server(String path, com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, handler);
        return server;
    }

    private static String base(HttpServer server, String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class StubEmbedder implements TextEmbedder {
        @Override public boolean isConfigured() { return false; }
        @Override public List<float[]> embed(List<String> texts) { return texts.stream().map(t -> new float[]{1}).toList(); }
        @Override public String modelName() { return "local"; }
        @Override public String status() { return "local"; }
        @Override public void close() { }
    }
}
