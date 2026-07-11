package com.simplerag.search;

import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SemanticHighlight;

import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Locates semantic sub-spans without owning index state or ranking policy. */
public final class SemanticHighlightService {
    private final TextEmbedder embedder;
    private final SemanticScorer scorer;
    private String cacheQuery = "";
    private final Map<String, List<SemanticHighlight>> cache = new HashMap<>();

    public SemanticHighlightService(TextEmbedder embedder, SemanticScorer scorer) {
        this.embedder = embedder;
        this.scorer = scorer;
    }

    public List<SemanticHighlight> locate(String query, DocumentChunk chunk, int limit, boolean semanticCompatible)
            throws IOException {
        String cleaned = query == null ? "" : query.strip();
        if (cleaned.isEmpty() || chunk == null || !chunk.hasEmbedding() || !semanticCompatible || limit <= 0) {
            return List.of();
        }
        List<SemanticHighlight> cached = cached(cleaned, chunk, limit);
        if (cached != null) return cached;
        List<SemanticHighlight> located = locateUncached(cleaned, chunk, limit);
        remember(cleaned, chunk, limit, located);
        return located;
    }

    public synchronized void clear() {
        cache.clear();
        cacheQuery = "";
    }

    private synchronized List<SemanticHighlight> cached(String query, DocumentChunk chunk, int limit) {
        return query.equals(cacheQuery) ? cache.get(chunk.id() + "@" + limit) : null;
    }

    private synchronized void remember(String query, DocumentChunk chunk, int limit,
                                       List<SemanticHighlight> highlights) {
        if (!query.equals(cacheQuery)) {
            cache.clear();
            cacheQuery = query;
        }
        cache.put(chunk.id() + "@" + limit, highlights);
    }

    private List<SemanticHighlight> locateUncached(String query, DocumentChunk chunk, int limit) throws IOException {
        float[] queryVector = embedder.embed(List.of(query)).get(0);
        List<TextSpan> candidates = candidateSpans(chunk.content());
        if (candidates.isEmpty()) return List.of();
        List<float[]> vectors = embedder.embed(candidates.stream().map(TextSpan::text).toList());
        List<ScoredSpan> scored = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            TextSpan span = candidates.get(i);
            double similarity = Math.max(0.0, scorer.score(queryVector, vectors.get(i)));
            double lengthPenalty = Math.min(0.055, Math.log1p(span.text().length() / 90.0) * 0.018);
            scored.add(new ScoredSpan(span, similarity, similarity - lengthPenalty));
        }
        scored.sort(Comparator.comparingDouble(ScoredSpan::rankingScore).reversed());
        List<SemanticHighlight> selected = new ArrayList<>(limit);
        for (ScoredSpan item : scored) {
            if (item.similarity() < 0.18) continue;
            boolean overlaps = selected.stream().anyMatch(existing ->
                    item.span().start() < existing.endOffset() && item.span().end() > existing.startOffset());
            if (!overlaps) {
                selected.add(new SemanticHighlight(item.span().start(), item.span().end(), item.similarity()));
                if (selected.size() == limit) break;
            }
        }
        selected.sort(Comparator.comparingInt(SemanticHighlight::startOffset));
        return List.copyOf(selected);
    }

    private static List<TextSpan> candidateSpans(String content) {
        List<TextSpan> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher paragraphs = Pattern.compile("(?s)\\S.*?(?=\\R\\s*\\R|\\z)").matcher(content);
        while (paragraphs.find() && result.size() < 80) {
            addSpan(result, seen, content, paragraphs.start(), paragraphs.end());
            BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
            String paragraph = content.substring(paragraphs.start(), paragraphs.end());
            sentences.setText(paragraph);
            int sentenceStart = sentences.first();
            for (int sentenceEnd = sentences.next(); sentenceEnd != BreakIterator.DONE;
                 sentenceStart = sentenceEnd, sentenceEnd = sentences.next()) {
                addSpan(result, seen, content, paragraphs.start() + sentenceStart, paragraphs.start() + sentenceEnd);
            }
        }
        List<TextSpan> lines = new ArrayList<>();
        Matcher lineMatcher = Pattern.compile("(?m)^.*(?:\\R|$)").matcher(content);
        while (lineMatcher.find() && lines.size() < 120) {
            int start = lineMatcher.start();
            int end = lineMatcher.end();
            while (end > start && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) end--;
            if (end > start && !content.substring(start, end).isBlank()) {
                lines.add(new TextSpan(start, end, content.substring(start, end).strip()));
                addSpan(result, seen, content, start, end);
            }
        }
        for (int i = 0; i < lines.size() && result.size() < 120; i += 2) {
            int endIndex = Math.min(lines.size() - 1, i + 3);
            if (endIndex > i) addSpan(result, seen, content, lines.get(i).start(), lines.get(endIndex).end());
        }
        if (result.isEmpty() && !content.isBlank()) addSpan(result, seen, content, 0, content.length());
        return result;
    }

    private static void addSpan(List<TextSpan> result, Set<String> seen, String content, int start, int end) {
        while (start < end && Character.isWhitespace(content.charAt(start))) start++;
        while (end > start && Character.isWhitespace(content.charAt(end - 1))) end--;
        if (end - start < 8 || end - start > 700) return;
        String key = start + ":" + end;
        if (seen.add(key)) result.add(new TextSpan(start, end, content.substring(start, end)));
    }

    private record TextSpan(int start, int end, String text) { }
    private record ScoredSpan(TextSpan span, double similarity, double rankingScore) { }
}
