package com.simplerag.search;

import com.simplerag.model.DocumentChunk;

import java.util.Map;
import java.util.Set;

/** Existing TF-IDF, exact-text, filename and concept scoring policy. */
public final class LexicalScorer {
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            "java", "kt", "kts", "py", "js", "jsx", "ts", "tsx", "go", "rs", "c", "h",
            "cpp", "hpp", "cs", "php", "rb", "swift", "scala", "sql", "sh", "bash", "ps1",
            "bat", "cmd", "css", "scss", "vue", "svelte");
    private final LexicalFeatureExtractor features;

    public LexicalScorer(LexicalFeatureExtractor features) { this.features = features; }

    public Score score(QueryAnalyzer.AnalyzedQuery query, DocumentChunk chunk,
                       Map<String, Double> documentTokens, Map<String, Double> documentVector,
                       double documentNorm) {
        double cosine = cosine(query.vector(), query.vectorNorm(), documentVector, documentNorm);
        String normalizedContent = features.normalize(chunk.content());
        String normalizedName = features.normalize(chunk.fileName());
        String normalizedPath = features.normalize(chunk.path());
        double exactBoost = normalizedContent.contains(query.normalizedText()) ? 0.24 : 0.0;
        long nameMatches = query.tokens().keySet().stream()
                .filter(token -> !token.startsWith("concept:") && normalizedName.contains(token))
                .count();
        long pathMatches = query.tokens().keySet().stream()
                .filter(token -> !token.startsWith("concept:") && normalizedPath.contains(token))
                .count();
        double nameBoost = nameMatches * (query.metadataFocused() ? 0.09 : 0.035);
        double pathBoost = Math.min(0.18, pathMatches * (query.locationIntent() ? 0.055 : 0.02));
        long conceptMatches = query.concepts().stream().filter(documentTokens::containsKey).count();
        double conceptBoost = Math.min(0.30, conceptMatches * 0.10);
        boolean codeFile = CODE_EXTENSIONS.contains(chunk.extension().toLowerCase(java.util.Locale.ROOT));
        double codeFileBoost = query.codeIntent() && codeFile ? 0.09 : 0.0;
        long declarationMatches = query.tokens().keySet().stream()
                .filter(token -> !token.startsWith("concept:") && token.length() >= 3)
                .filter(token -> declarationContains(normalizedContent, token)).count();
        double declarationBoost = Math.min(0.32, declarationMatches * 0.16);
        return new Score(cosine * 0.74 + exactBoost + nameBoost + pathBoost + conceptBoost
                + codeFileBoost + declarationBoost, conceptMatches, exactBoost > 0,
                nameMatches + pathMatches > 0, declarationMatches > 0, codeFileBoost > 0);
    }

    private static boolean declarationContains(String content, String token) {
        return content.contains("class " + token) || content.contains("interface " + token)
                || content.contains("record " + token) || content.contains("enum " + token)
                || content.contains("def " + token) || content.contains("function " + token)
                || content.contains(" " + token + "(") || content.startsWith(token + "(");
    }

    private static double cosine(Map<String, Double> left, double leftNorm,
                                 Map<String, Double> right, double rightNorm) {
        if (leftNorm == 0 || rightNorm == 0) return 0;
        Map<String, Double> smaller = left.size() <= right.size() ? left : right;
        Map<String, Double> larger = left.size() <= right.size() ? right : left;
        double dot = 0;
        for (Map.Entry<String, Double> entry : smaller.entrySet()) {
            dot += entry.getValue() * larger.getOrDefault(entry.getKey(), 0.0);
        }
        return dot / (leftNorm * rightNorm);
    }

    public record Score(double value, long conceptMatches, boolean exactMatch,
                        boolean metadataMatch, boolean declarationMatch, boolean codeFileMatch) { }
}
