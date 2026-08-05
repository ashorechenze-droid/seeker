package com.simplerag.search;

import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SearchResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridRetrievalTest {
    @TempDir Path temp;

    @Test
    void bm25RetrievesCrossLanguageConceptWithoutEmbeddings() throws Exception {
        Files.writeString(temp.resolve("database-connection.md"),
                "MySQL connection through JDBC and a HikariCP connection pool");
        Files.writeString(temp.resolve("unrelated.md"), "UI navigation and keyboard shortcuts");
        SemanticSearchEngine engine = new SemanticSearchEngine(new DisabledEmbedder());
        engine.index(List.of(temp), null);

        List<SearchResult> results = engine.search("\u5982\u4f55\u8fde\u63a5\u6570\u636e\u5e93", 5, "all",
                RetrievalStrategy.BM25);

        assertEquals("database-connection.md", results.get(0).chunk().fileName());
        assertTrue(results.get(0).reason().startsWith("BM25"));
    }

    @Test
    void rrfContainsCandidatesFromIndependentSparseAndDenseBranches() {
        LexicalFeatureExtractor features = new LexicalFeatureExtractor();
        DocumentChunk sparse = chunk("sparse", "database.md", "database connection jdbc", new float[]{0, 1});
        DocumentChunk dense = chunk("dense", "semantic.md", "unrelated semantic passage", new float[]{1, 0});
        IndexedDocuments indexed = index(List.of(sparse, dense), features);
        QueryAnalyzer.AnalyzedQuery query = new QueryAnalyzer(features)
                .analyze("database", indexed.documentFrequency(), indexed.documents().size());
        RetrievalPipeline pipeline = new RetrievalPipeline(new SemanticScorer(),
                new LexicalScorer(features), new FeatureReranker());

        List<SearchResult> results = pipeline.search(query, indexed.documents(), indexed.documentFrequency(),
                new float[]{1, 0}, 10, "*", RetrievalStrategy.RRF);

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(hit -> hit.chunk().id().equals("sparse")));
        assertTrue(results.stream().anyMatch(hit -> hit.chunk().id().equals("dense")));
    }

    @Test
    void featureRerankerPromotesStrongMetadataAndCodeEvidence() {
        QueryAnalyzer.AnalyzedQuery query = new QueryAnalyzer(new LexicalFeatureExtractor())
                .analyze("where is UserService implementation", Map.of(), 2);
        RetrievalDocument distractor = document(chunk("d", "notes.md", "general notes", null));
        RetrievalDocument relevant = document(chunk("r", "UserService.java", "class UserService {}", null));
        RetrievalCandidate weak = new RetrievalCandidate(distractor, 0.70, 0.50, 0.80, 0.05, 0.80);
        RetrievalCandidate strong = new RetrievalCandidate(relevant, 0.60, 0.40, 0.70, 0.95, 0.70);

        List<RetrievalCandidate> ranked = new FeatureReranker().rerank(query, List.of(weak, strong), 2);

        assertEquals("r", ranked.get(0).document().chunk().id());
    }

    private static IndexedDocuments index(List<DocumentChunk> chunks, LexicalFeatureExtractor features) {
        List<Map<String, Double>> tokens = chunks.stream()
                .map(chunk -> features.weightedTokens(chunk.fileName() + " " + chunk.sourceLocation()
                        + " " + chunk.content(), false)).toList();
        Map<String, Integer> df = new HashMap<>();
        tokens.forEach(items -> items.keySet().forEach(token -> df.merge(token, 1, Integer::sum)));
        List<RetrievalDocument> documents = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Double> vector = features.tfIdf(tokens.get(i), df, chunks.size());
            double length = tokens.get(i).values().stream().mapToDouble(Double::doubleValue).sum();
            documents.add(new RetrievalDocument(chunks.get(i), tokens.get(i), vector,
                    features.norm(vector), Math.max(1, length)));
        }
        return new IndexedDocuments(List.copyOf(documents), Map.copyOf(df));
    }

    private static RetrievalDocument document(DocumentChunk chunk) {
        return new RetrievalDocument(chunk, Map.of(), Map.of(), 0, 1);
    }

    private static DocumentChunk chunk(String id, String fileName, String content, float[] embedding) {
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1);
        return new DocumentChunk(id, "/kb/" + fileName, "/kb", fileName, extension,
                1, 2, "L1-2", content, 1, embedding);
    }

    private record IndexedDocuments(List<RetrievalDocument> documents,
                                    Map<String, Integer> documentFrequency) { }

    private static final class DisabledEmbedder implements TextEmbedder {
        public boolean isConfigured() { return false; }
        public List<float[]> embed(List<String> texts) throws IOException { throw new IOException("disabled"); }
        public String modelName() { return "disabled"; }
        public String status() { return "disabled"; }
        public void close() { }
    }
}
