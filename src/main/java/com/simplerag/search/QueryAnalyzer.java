package com.simplerag.search;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

/** Converts query text into the exact lexical representation consumed by scorers. */
public final class QueryAnalyzer {
    private static final Pattern CODE_INTENT = Pattern.compile(
            "(?i)(代码|源码|实现|定义|类|方法|函数|接口|调用链|code|source|implementation|defined|definition|class|method|function)");
    private static final Pattern LOCATION_INTENT = Pattern.compile(
            "(?i)(在哪|哪里|哪儿|位置|哪个文件|什么文件|怎么找到|帮我找|定位|where|which file|location|find|located)");
    private static final Pattern BOILERPLATE = Pattern.compile(
            "(?i)(请帮我找|帮我找|请问|请帮我|帮我|告诉我|我想知道|相关的?|对应的?|具体的?|代码|源码|实现|定义|在哪(里|儿)?|位置|哪个文件|什么文件|怎么找到|定位|where\\s+(is|are|can\\s+i\\s+find)|which\\s+file|location\\s+of|find\\s+(the\\s+)?(code|implementation)|source\\s+code)");
    private final LexicalFeatureExtractor features;

    public QueryAnalyzer(LexicalFeatureExtractor features) { this.features = features; }

    public AnalyzedQuery analyze(String query, Map<String, Integer> documentFrequency, int documents) {
        String cleaned = query == null ? "" : query.strip();
        boolean codeIntent = CODE_INTENT.matcher(cleaned).find();
        boolean locationIntent = LOCATION_INTENT.matcher(cleaned).find();
        String subject = BOILERPLATE.matcher(cleaned).replaceAll(" ").replaceAll("[？?，,：:]+", " ")
                .replaceAll("\\s+", " ").strip();
        if (subject.isBlank()) subject = cleaned;
        Map<String, Double> tokens = features.weightedTokens(subject, true);
        Map<String, Double> vector = features.tfIdf(tokens, documentFrequency, documents);
        Set<String> concepts = tokens.keySet().stream().filter(token -> token.startsWith("concept:"))
                .collect(Collectors.toUnmodifiableSet());
        return new AnalyzedQuery(cleaned, subject, features.normalize(subject), tokens, vector,
                features.norm(vector), concepts, codeIntent, locationIntent);
    }

    public record AnalyzedQuery(String text, String semanticText, String normalizedText,
                                Map<String, Double> tokens, Map<String, Double> vector,
                                double vectorNorm, Set<String> concepts,
                                boolean codeIntent, boolean locationIntent) {
        public boolean empty() { return text.isEmpty(); }
        public boolean metadataFocused() { return codeIntent || locationIntent; }
    }
}
