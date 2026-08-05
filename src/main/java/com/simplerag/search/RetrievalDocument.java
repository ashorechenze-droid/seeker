package com.simplerag.search;

import com.simplerag.model.DocumentChunk;

import java.util.Map;

/** Immutable indexed representation shared by sparse, dense and second-stage retrieval. */
public record RetrievalDocument(DocumentChunk chunk, Map<String, Double> tokens,
                         Map<String, Double> tfIdfVector, double tfIdfNorm,
                         double length) {
}
