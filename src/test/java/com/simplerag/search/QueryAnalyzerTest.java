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

    @Test
    void removesLocationBoilerplateButPreservesTheActualCodeSubject() {
        QueryAnalyzer analyzer = new QueryAnalyzer(new LexicalFeatureExtractor());

        QueryAnalyzer.AnalyzedQuery query = analyzer.analyze("请帮我找接口超时重试相关代码在哪？", Map.of(), 8);

        assertTrue(query.codeIntent());
        assertTrue(query.locationIntent());
        assertEquals("接口超时重试", query.semanticText());
        assertTrue(query.concepts().contains("concept:error-handling"));
        assertTrue(query.tokens().keySet().stream().noneMatch(token -> token.contains("在哪")));
    }
}
