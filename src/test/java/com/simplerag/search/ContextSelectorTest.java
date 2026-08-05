package com.simplerag.search;

import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextSelectorTest {
    @Test
    void limitsEachDocumentAndKeepsDiverseContext() {
        DocumentChunk a1 = chunk("a1", "/kb/a.md", 1, 5, "One \u00b7 L1-5", "alpha first section");
        DocumentChunk a2 = chunk("a2", "/kb/a.md", 10, 15, "Two \u00b7 L10-15", "alpha second section");
        DocumentChunk a3 = chunk("a3", "/kb/a.md", 20, 25, "Three \u00b7 L20-25", "alpha third section");
        DocumentChunk b1 = chunk("b1", "/kb/b.md", 1, 5, "One \u00b7 L1-5", "beta independent evidence");
        List<SearchResult> ranked = List.of(result(a1, .99), result(a2, .98), result(a3, .97), result(b1, .90));

        List<SearchResult> selected = new ContextSelector().select(ranked, List.of(a1, a2, a3, b1), 4);
        Map<String, Long> perDocument = selected.stream().collect(Collectors.groupingBy(
                hit -> hit.chunk().path(), Collectors.counting()));

        assertTrue(perDocument.getOrDefault("/kb/a.md", 0L) <= 2);
        assertEquals(1L, perDocument.getOrDefault("/kb/b.md", 0L));
    }

    @Test
    void expandsAdjacentChunksButRejectsOverlappingRanges() {
        DocumentChunk anchor = chunk("a", "/kb/a.md", 1, 10, "Section \u00b7 L1-10", "anchor evidence");
        DocumentChunk overlap = chunk("o", "/kb/a.md", 8, 15, "Section \u00b7 L8-15", "overlap duplicate");

        SearchResult selected = new ContextSelector().select(List.of(result(anchor, 1)),
                List.of(anchor, overlap), 1).get(0);

        assertTrue(selected.chunk().content().contains("anchor evidence"));
        assertFalse(selected.chunk().content().contains("overlap duplicate"));

        DocumentChunk adjacent = chunk("n", "/kb/a.md", 11, 15, "Section \u00b7 L11-15", "adjacent evidence");
        selected = new ContextSelector().select(List.of(result(anchor, 1)),
                List.of(anchor, adjacent), 1).get(0);
        assertTrue(selected.chunk().content().contains("adjacent evidence"));
    }

    private static SearchResult result(DocumentChunk chunk, double score) {
        return new SearchResult(chunk, score, "test");
    }

    private static DocumentChunk chunk(String id, String path, int start, int end,
                                       String location, String content) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return new DocumentChunk(id, path, "/kb", file, "md", start, end,
                location, content, 1, null);
    }
}
