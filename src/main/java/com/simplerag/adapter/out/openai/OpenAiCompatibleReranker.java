package com.simplerag.adapter.out.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplerag.application.diagnostics.DiagnosticSink;
import com.simplerag.rag.ModelApiConfig;
import com.simplerag.search.QueryAnalyzer;
import com.simplerag.search.RetrievalCandidate;
import com.simplerag.search.SecondStageReranker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** /rerank-compatible cross-encoder adapter with deterministic local fallback. */
public final class OpenAiCompatibleReranker implements SecondStageReranker {
    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private final SecondStageReranker local;
    private final Supplier<ModelApiConfig> config;
    private final DiagnosticSink diagnostics;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper json = new ObjectMapper();
    private volatile String activeName;

    public OpenAiCompatibleReranker(SecondStageReranker local, Supplier<ModelApiConfig> config,
                                    DiagnosticSink diagnostics) {
        this.local = local;
        this.config = config;
        this.diagnostics = diagnostics == null ? DiagnosticSink.noop() : diagnostics;
        this.activeName = local.name();
    }

    @Override public List<RetrievalCandidate> rerank(QueryAnalyzer.AnalyzedQuery query,
                                                      List<RetrievalCandidate> candidates, int limit) {
        ModelApiConfig current = config.get();
        if (!current.enabled() || candidates.isEmpty()) return local(query, candidates, limit);
        long started = System.nanoTime();
        try {
            current.validate();
            ObjectNode payload = json.createObjectNode();
            payload.put("model", current.model());
            // Recall runs on the cleaned subject, so ranking must too. Sending the raw text fed the
            // cross-encoder boilerplate ("请帮我找…在哪") that dilutes the actual query terms.
            payload.put("query", rerankQuery(query));
            payload.put("top_n", Math.min(limit, candidates.size()));
            ArrayNode documents = payload.putArray("documents");
            candidates.forEach(candidate -> documents.add(candidate.document().chunk().fileName() + "\n"
                    + candidate.document().chunk().sourceLocation() + "\n"
                    + candidate.document().chunk().content()));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint(current.normalizedBaseUrl())))
                    .timeout(TIMEOUT).header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            if (!current.apiKey().isBlank()) builder.header("Authorization", "Bearer " + current.apiKey());
            HttpResponse<String> response = http.send(builder.POST(HttpRequest.BodyPublishers.ofString(
                    json.writeValueAsString(payload))).build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(response.body() == null || response.body().isBlank() ? "{}" : response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = body.path("error").path("message").asText(
                        body.path("message").asText("HTTP " + response.statusCode()));
                throw new IllegalStateException(message);
            }
            JsonNode results = body.path("results");
            if (!results.isArray()) results = body.path("data");
            if (!results.isArray()) throw new IllegalStateException("响应缺少 results 数组");
            List<RetrievalCandidate> ranked = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (JsonNode result : results) {
                int index = result.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size() || !seen.add(index)) continue;
                double score = result.has("relevance_score")
                        ? result.path("relevance_score").asDouble() : result.path("score").asDouble();
                ranked.add(candidates.get(index).withFinalScore(clamp(score)));
            }
            if (ranked.isEmpty()) throw new IllegalStateException("响应未包含有效排序结果");
            ranked.sort(Comparator.comparingDouble(RetrievalCandidate::finalScore).reversed());
            // Some services return fewer than top_n; retain the remaining local candidates at the end.
            List<RetrievalCandidate> localRanked = local.rerank(query, candidates, candidates.size());
            for (RetrievalCandidate candidate : localRanked) {
                int original = originalIndex(candidates, candidate);
                if (original >= 0 && !seen.contains(original)) {
                    ranked.add(candidate.withFinalScore(candidate.finalScore() * 0.25));
                }
            }
            activeName = "api-reranker · " + current.model();
            record("ok", started, current);
            return ranked.size() <= limit ? List.copyOf(ranked) : List.copyOf(ranked.subList(0, limit));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            record("interrupted", started, current);
            return local(query, candidates, limit);
        } catch (Exception failure) {
            diagnostics.record("rerank fallback", "remote-api", failure.getClass().getSimpleName(),
                    Map.of("reason", failure.getMessage() == null ? "rerank failed" : failure.getMessage()));
            record("fallback", started, current);
            return local(query, candidates, limit);
        }
    }

    private List<RetrievalCandidate> local(QueryAnalyzer.AnalyzedQuery query,
                                            List<RetrievalCandidate> candidates, int limit) {
        activeName = local.name();
        return local.rerank(query, candidates, limit);
    }

    @Override public String name() { return activeName; }

    private void record(String outcome, long started, ModelApiConfig config) {
        diagnostics.record("adapter latency", "remote-api", "rerank", Map.of(
                "model", config.model(), "outcome", outcome,
                "latencyMs", Long.toString((System.nanoTime() - started) / 1_000_000L)));
    }

    private static String endpoint(String baseUrl) {
        return baseUrl.endsWith("/rerank") ? baseUrl : baseUrl + "/rerank";
    }

    private static int originalIndex(List<RetrievalCandidate> candidates, RetrievalCandidate target) {
        String id = target.document().chunk().id();
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).document().chunk().id().equals(id)) return i;
        }
        return -1;
    }

    private static String rerankQuery(QueryAnalyzer.AnalyzedQuery query) {
        String semantic = query.semanticText();
        return semantic == null || semantic.isBlank() ? query.text() : semantic;
    }

    /** Rejects NaN and infinities before they reach the comparator and make ordering undefined. */
    private static double clamp(double value) {
        if (Double.isNaN(value)) return 0;
        return Math.max(0, Math.min(1, value));
    }
}
