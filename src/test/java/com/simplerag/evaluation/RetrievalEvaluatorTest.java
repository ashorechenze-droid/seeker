package com.simplerag.evaluation;

import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.search.SemanticSearchEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalEvaluatorTest {
    @TempDir Path temp;

    @Test
    void computesRepeatableMetricsAndWritesPolicyVersion() throws Exception {
        Files.writeString(temp.resolve("alpha.md"), "alpha exact_key useful passage");
        Files.writeString(temp.resolve("forbidden.md"), "unrelated material");
        SemanticSearchEngine engine = new SemanticSearchEngine(new DisabledEmbedder());
        engine.index(List.of(temp), null);
        RetrievalEvaluationCase item = new RetrievalEvaluationCase("exact", "exact_key", "code",
                List.of("alpha.md"), List.of("useful passage"), List.of("forbidden.md"), "identifier");
        RetrievalEvaluationDataset dataset = new RetrievalEvaluationDataset("test", 1, 1, 1, 1, List.of(item));

        RetrievalEvaluator evaluator = new RetrievalEvaluator();
        RetrievalEvaluationReport report = evaluator.evaluate(dataset, engine, 12.5);

        assertEquals(1.0, report.recallAt5());
        assertEquals(1.0, report.mrrAt10());
        assertEquals(1.0, report.ndcgAt10());
        assertFalse(report.cases().get(0).forbiddenResultReturned());
        assertEquals(3, report.rankingPolicyVersion());
        assertTrue(report.estimatedMemoryBytesPerThousandChunks() > 0);
        assertDoesNotThrow(() -> evaluator.verifyThresholds(dataset, report));
        RetrievalAblationReport ablations = evaluator.evaluateAblations(dataset, engine, 12.5);
        assertEquals(java.util.Set.of("BM25", "RRF", "RRF_RERANK"),
                ablations.strategies().keySet());
        Path output = temp.resolve("reports/report.json");
        evaluator.save(report, output);
        assertTrue(Files.readString(output).contains("\"rankingPolicyVersion\" : 3"));
    }

    private static final class DisabledEmbedder implements TextEmbedder {
        public List<float[]> embed(List<String> texts) throws IOException { throw new IOException("disabled"); }
        public boolean isConfigured() { return false; }
        public String status() { return "disabled"; }
        public String modelName() { return "disabled"; }
        public void close() { }
    }
}
