package com.simplerag.bootstrap;

import com.simplerag.adapter.out.onnx.Langchain4jOnnxEmbeddingProvider;
import com.simplerag.evaluation.RetrievalEvaluationDataset;
import com.simplerag.evaluation.RetrievalEvaluationReport;
import com.simplerag.evaluation.RetrievalEvaluator;
import com.simplerag.search.SemanticSearchEngine;

import java.nio.file.Path;
import java.util.List;

public final class RetrievalEvaluationMain {
    private RetrievalEvaluationMain() { }

    public static void main(String[] args) throws Exception {
        Path datasetPath = args.length > 0 ? Path.of(args[0]) : Path.of("examples", "evaluation", "retrieval-baseline.json");
        Path sourcePath = args.length > 1 ? Path.of(args[1]) : Path.of("examples", "knowledge");
        Path reportPath = args.length > 2 ? Path.of(args[2]) : Path.of("target", "evaluation", "retrieval-report.json");
        SemanticSearchEngine engine = new SemanticSearchEngine(new Langchain4jOnnxEmbeddingProvider());
        long started = System.nanoTime();
        engine.index(List.of(sourcePath.toAbsolutePath()), null);
        double indexingMillis = (System.nanoTime() - started) / 1_000_000.0;
        RetrievalEvaluator evaluator = new RetrievalEvaluator();
        RetrievalEvaluationDataset dataset = RetrievalEvaluationDataset.load(datasetPath);
        RetrievalEvaluationReport report = evaluator.evaluate(dataset, engine, indexingMillis);
        evaluator.save(report, reportPath);
        evaluator.verifyThresholds(dataset, report);
        System.out.printf("Recall@5=%.3f MRR@10=%.3f nDCG@10=%.3f report=%s%n",
                report.recallAt5(), report.mrrAt10(), report.ndcgAt10(), reportPath.toAbsolutePath());
    }
}
