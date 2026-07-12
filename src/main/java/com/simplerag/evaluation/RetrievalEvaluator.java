package com.simplerag.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SearchResult;
import com.simplerag.search.SemanticSearchEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RetrievalEvaluator {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public RetrievalEvaluationReport evaluate(RetrievalEvaluationDataset dataset,
                                               SemanticSearchEngine engine,
                                               double indexingMillis) {
        List<RetrievalEvaluationReport.CaseResult> results = new ArrayList<>();
        double recall = 0, mrr = 0, ndcg = 0, coldNanos = 0, cachedNanos = 0;
        for (RetrievalEvaluationCase item : dataset.cases()) {
            long started = System.nanoTime();
            List<SearchResult> ranked = engine.search(item.query(), 10, "全部");
            coldNanos += System.nanoTime() - started;
            started = System.nanoTime();
            engine.search(item.query(), 10, "全部");
            cachedNanos += System.nanoTime() - started;

            List<String> documents = ranked.stream().map(result -> result.chunk().fileName()).toList();
            double caseRecall = recallAt(documents, item.expectedDocuments(), 5);
            double caseMrr = reciprocalRank(documents, item.expectedDocuments(), 10);
            double caseNdcg = ndcgAt(ranked, item, 10);
            boolean forbidden = documents.stream().anyMatch(item.mustNotReturn()::contains);
            recall += caseRecall;
            mrr += caseMrr;
            ndcg += caseNdcg;
            results.add(new RetrievalEvaluationReport.CaseResult(item.id(), caseRecall, caseMrr,
                    caseNdcg, forbidden, documents));
        }
        int count = dataset.cases().size();
        return new RetrievalEvaluationReport(dataset.name(), dataset.version(),
                engine.rankingPolicyVersion(), Instant.now().toString(), count,
                recall / count, mrr / count, ndcg / count,
                nanosToMillis(coldNanos / count), nanosToMillis(cachedNanos / count), indexingMillis,
                estimateMemoryPerThousandChunks(engine.snapshot().chunks()), results);
    }

    public void save(RetrievalEvaluationReport report, Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        JSON.writeValue(output.toFile(), report);
    }

    public void verifyThresholds(RetrievalEvaluationDataset dataset, RetrievalEvaluationReport report) {
        List<String> failures = new ArrayList<>();
        if (report.recallAt5() < dataset.minimumRecallAt5()) failures.add("Recall@5");
        if (report.mrrAt10() < dataset.minimumMrrAt10()) failures.add("MRR@10");
        if (report.ndcgAt10() < dataset.minimumNdcgAt10()) failures.add("nDCG@10");
        if (report.cases().stream().anyMatch(RetrievalEvaluationReport.CaseResult::forbiddenResultReturned))
            failures.add("mustNotReturn");
        if (!failures.isEmpty()) throw new IllegalStateException("检索质量门禁失败: " + String.join(", ", failures));
    }

    private static double recallAt(List<String> ranked, List<String> expected, int limit) {
        Set<String> hits = new HashSet<>(ranked.subList(0, Math.min(limit, ranked.size())));
        return expected.stream().filter(hits::contains).count() / (double) expected.size();
    }

    private static double reciprocalRank(List<String> ranked, List<String> expected, int limit) {
        for (int i = 0; i < Math.min(limit, ranked.size()); i++) {
            if (expected.contains(ranked.get(i))) return 1.0 / (i + 1);
        }
        return 0;
    }

    private static double ndcgAt(List<SearchResult> ranked, RetrievalEvaluationCase item, int limit) {
        double dcg = 0;
        for (int i = 0; i < Math.min(limit, ranked.size()); i++) {
            int relevance = relevance(ranked.get(i).chunk(), item);
            dcg += (Math.pow(2, relevance) - 1) / log2(i + 2);
        }
        int passageMatches = item.expectedPassages().isEmpty() ? 0 : 1;
        List<Integer> ideal = new ArrayList<>();
        for (int i = 0; i < item.expectedDocuments().size(); i++) ideal.add(i < passageMatches ? 2 : 1);
        ideal.sort(java.util.Comparator.reverseOrder());
        double idcg = 0;
        for (int i = 0; i < Math.min(limit, ideal.size()); i++)
            idcg += (Math.pow(2, ideal.get(i)) - 1) / log2(i + 2);
        return idcg == 0 ? 0 : Math.min(1.0, dcg / idcg);
    }

    private static int relevance(DocumentChunk chunk, RetrievalEvaluationCase item) {
        if (!item.expectedDocuments().contains(chunk.fileName())) return 0;
        String content = chunk.content().toLowerCase(Locale.ROOT);
        boolean passage = item.expectedPassages().stream()
                .map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(content::contains);
        return passage ? 2 : 1;
    }

    private static double estimateMemoryPerThousandChunks(List<DocumentChunk> chunks) {
        if (chunks.isEmpty()) return 0;
        long bytes = 0;
        for (DocumentChunk chunk : chunks) {
            bytes += (long) chunk.content().length() * 2;
            bytes += (long) chunk.id().length() * 2 + (long) chunk.path().length() * 2 + 64;
            if (chunk.embedding() != null) bytes += (long) chunk.embedding().length * Float.BYTES;
        }
        return bytes * 1000.0 / chunks.size();
    }

    private static double log2(double value) { return Math.log(value) / Math.log(2); }
    private static double nanosToMillis(double nanos) { return nanos / 1_000_000.0; }
}
