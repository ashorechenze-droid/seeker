package com.simplerag.search;

import java.util.List;

/** Replaceable second-stage ranker; an ONNX cross-encoder can implement this contract later. */
public interface SecondStageReranker {
    List<RetrievalCandidate> rerank(QueryAnalyzer.AnalyzedQuery query,
                                    List<RetrievalCandidate> candidates,
                                    int limit);

    String name();
}
