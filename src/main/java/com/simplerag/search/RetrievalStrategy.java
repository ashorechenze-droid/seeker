package com.simplerag.search;

/** Retrieval variants used by quality evaluation and ablation reports. */
public enum RetrievalStrategy {
    BM25,
    DENSE,
    RRF,
    RRF_RERANK;

    public boolean usesBm25() {
        return this != DENSE;
    }

    public boolean usesDense() {
        return this != BM25;
    }

    public boolean usesFusion() {
        return this == RRF || this == RRF_RERANK;
    }

    public boolean usesReranker() {
        return this == RRF_RERANK;
    }
}
