package com.simplerag.search;

import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SearchResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** MMR-style diversity, per-document quotas and section-aware neighbour expansion for RAG context. */
public final class ContextSelector {
    private static final int MAX_PER_DOCUMENT = 2;
    private static final int MAX_EXPANDED_CHARACTERS = 2_800;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]{2,}");

    public List<SearchResult> select(List<SearchResult> ranked, List<DocumentChunk> corpus, int limit) {
        if (ranked == null || ranked.isEmpty() || limit <= 0) return List.of();
        List<SearchResult> pool = ranked.subList(0, Math.min(ranked.size(), Math.max(20, limit * 4)));
        List<SearchResult> selected = new ArrayList<>();
        Map<String, Integer> perDocument = new HashMap<>();
        Set<String> used = new HashSet<>();

        while (selected.size() < limit) {
            SearchResult best = null;
            double bestMmr = Double.NEGATIVE_INFINITY;
            for (SearchResult candidate : pool) {
                if (used.contains(candidate.chunk().id())) continue;
                if (perDocument.getOrDefault(candidate.chunk().path(), 0) >= MAX_PER_DOCUMENT) continue;
                double redundancy = selected.stream().mapToDouble(existing -> similarity(candidate.chunk(), existing.chunk()))
                        .max().orElse(0);
                double sameDocumentPenalty = selected.stream().anyMatch(existing ->
                        existing.chunk().path().equals(candidate.chunk().path())) ? 0.08 : 0;
                double mmr = candidate.score() * 0.78 - redundancy * 0.22 - sameDocumentPenalty;
                if (mmr > bestMmr) {
                    bestMmr = mmr;
                    best = candidate;
                }
            }
            if (best == null) break;
            selected.add(best);
            used.add(best.chunk().id());
            perDocument.merge(best.chunk().path(), 1, Integer::sum);
        }

        Set<String> consumed = new HashSet<>();
        List<SearchResult> expanded = new ArrayList<>();
        for (SearchResult hit : selected) {
            if (consumed.contains(hit.chunk().id())) continue;
            List<DocumentChunk> family = corpus.stream()
                    .filter(chunk -> chunk.path().equals(hit.chunk().path()))
                    .filter(chunk -> sectionKey(chunk).equals(sectionKey(hit.chunk())))
                    .sorted(Comparator.comparingInt(DocumentChunk::startLine))
                    .toList();
            DocumentChunk context = expand(hit.chunk(), family, consumed);
            expanded.add(new SearchResult(context, hit.score(), hit.reason() + " · MMR 去重与父章节邻接扩展"));
            if (expanded.size() >= limit) break;
        }
        return List.copyOf(expanded);
    }

    private static DocumentChunk expand(DocumentChunk anchor, List<DocumentChunk> family, Set<String> consumed) {
        int position = -1;
        for (int index = 0; index < family.size(); index++) {
            if (family.get(index).id().equals(anchor.id())) {
                position = index;
                break;
            }
        }
        if (position < 0) {
            consumed.add(anchor.id());
            return anchor;
        }
        LinkedHashSet<DocumentChunk> pieces = new LinkedHashSet<>();
        pieces.add(anchor);
        int characters = anchor.content().length();
        for (int distance = 1; distance <= 1; distance++) {
            int before = position - distance;
            int after = position + distance;
            if (before >= 0) characters = addIfUseful(pieces, family.get(before), characters);
            if (after < family.size()) characters = addIfUseful(pieces, family.get(after), characters);
        }
        List<DocumentChunk> ordered = pieces.stream()
                .sorted(Comparator.comparingInt(DocumentChunk::startLine)).toList();
        consumed.addAll(ordered.stream().map(DocumentChunk::id).toList());
        if (ordered.size() == 1) return anchor;
        DocumentChunk first = ordered.get(0);
        DocumentChunk last = ordered.get(ordered.size() - 1);
        String content = String.join("\n\n", ordered.stream().map(DocumentChunk::content).toList());
        if (content.length() > MAX_EXPANDED_CHARACTERS) content = content.substring(0, MAX_EXPANDED_CHARACTERS);
        String location = first.sourceLocation().equals(last.sourceLocation()) ? first.sourceLocation()
                : first.sourceLocation() + " — " + last.sourceLocation();
        return new DocumentChunk(anchor.id() + ":context", anchor.path(), anchor.root(), anchor.fileName(),
                anchor.extension(), first.startLine(), last.endLine(), location, content,
                anchor.modifiedAt(), null);
    }

    private static int addIfUseful(Set<DocumentChunk> pieces, DocumentChunk candidate, int characters) {
        if (characters + candidate.content().length() + 2 > MAX_EXPANDED_CHARACTERS) return characters;
        boolean overlaps = pieces.stream().anyMatch(existing -> rangesOverlap(existing, candidate));
        if (!overlaps) {
            pieces.add(candidate);
            return characters + candidate.content().length() + 2;
        }
        return characters;
    }

    private static boolean rangesOverlap(DocumentChunk first, DocumentChunk second) {
        return first.startLine() <= second.endLine() && second.startLine() <= first.endLine();
    }

    private static String sectionKey(DocumentChunk chunk) {
        String location = chunk.sourceLocation();
        int separator = location.lastIndexOf(" · ");
        return separator < 0 ? chunk.path() : chunk.path() + "\u0000" + location.substring(0, separator);
    }

    private static double similarity(DocumentChunk first, DocumentChunk second) {
        Set<String> left = terms(first.content());
        Set<String> right = terms(second.content());
        if (left.isEmpty() || right.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        double jaccard = intersection.size() / (double) union.size();
        if (first.path().equals(second.path())) jaccard = Math.max(jaccard, 0.35);
        return jaccard;
    }

    private static Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        var matcher = TOKEN.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) terms.add(matcher.group());
        return terms;
    }
}
