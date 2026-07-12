package com.simplerag.application.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.simplerag.application.runtime.ActiveKnowledgeContext;
import com.simplerag.application.runtime.ActiveKnowledgeRuntime;
import com.simplerag.search.IndexManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/** Builds a metadata-only report. No API key, prompt, chunk text or conversation is reachable here. */
public final class DiagnosticReportService {
    private final ActiveKnowledgeRuntime runtime;
    private final DiagnosticLog log;
    private final ObjectMapper json = new ObjectMapper();

    public DiagnosticReportService(ActiveKnowledgeRuntime runtime, DiagnosticLog log) {
        this.runtime = runtime;
        this.log = log;
    }

    public String reportJson() {
        ObjectNode root = json.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("generatedAt", Instant.now().toString());
        root.put("javaVersion", System.getProperty("java.version", ""));
        root.put("os", System.getProperty("os.name", "") + " " + System.getProperty("os.version", ""));
        ActiveKnowledgeContext context = runtime.currentOrNull();
        if (context != null) {
            ObjectNode active = root.putObject("activeKnowledge");
            active.put("knowledgeBaseId", context.knowledgeBaseId());
            active.put("sourceRevision", context.sourceRevision());
            active.put("publishedRevision", context.knowledgeBase().publishedIndexRevision());
            active.put("indexStatus", context.indexStatus().name());
            active.put("freshnessState", context.freshness().state().name());
            active.put("freshnessReason", context.freshness().reason());
            active.put("freshnessVerifiedAt", context.freshness().verifiedAt());
            IndexManifest manifest = context.indexHandle().engine().snapshot().manifest();
            if (manifest != null) {
                ObjectNode summary = active.putObject("manifest");
                summary.put("sourceRevision", manifest.sourceRevision());
                summary.put("sourceSetHash", manifest.sourceSetHash());
                summary.put("embeddingDimension", manifest.embeddingDimension());
                summary.put("chunkingVersion", manifest.chunkingVersion());
                summary.put("indexFormatVersion", manifest.indexFormatVersion());
                summary.put("builtAt", manifest.builtAt());
            }
        }
        ArrayNode events = root.putArray("events");
        for (DiagnosticEvent event : log.recentEvents()) {
            ObjectNode item = events.addObject();
            item.put("timestamp", event.timestamp());
            item.put("type", event.type());
            item.put("category", event.category());
            item.put("message", event.message());
            item.set("attributes", json.valueToTree(event.attributes()));
        }
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public void export(Path destination) throws IOException {
        Files.writeString(destination, reportJson(), StandardCharsets.UTF_8);
    }
}
