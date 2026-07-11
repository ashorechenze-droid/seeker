package com.simplerag.search;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryAnalyzerTest {
    @Test
    void preservesMixedLanguageIdentifiersAndConceptExpansion() {
        QueryAnalyzer analyzer = new QueryAnalyzer(new LexicalFeatureExtractor());

        QueryAnalyzer.AnalyzedQuery query = analyzer.analyze("如何配置 JDBC connectionPool", Map.of(), 4);

        assertEquals("如何配置 JDBC connectionPool", query.text());
        assertTrue(query.tokens().containsKey("jdbc"));
        assertTrue(query.tokens().containsKey("connection"));
        assertTrue(query.concepts().contains("concept:database-connection"));
        assertTrue(query.vectorNorm() > 0);
    }
}
