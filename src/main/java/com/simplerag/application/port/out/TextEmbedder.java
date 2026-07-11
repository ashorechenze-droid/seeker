package com.simplerag.application.port.out;

import com.simplerag.search.EmbeddingModelSignature;

import java.io.IOException;
import java.util.List;

public interface TextEmbedder extends AutoCloseable {
    boolean isConfigured();
    List<float[]> embed(List<String> texts) throws IOException;
    String modelName();
    String status();
    default int dimension() { return 0; }
    default EmbeddingModelSignature signature() {
        return new EmbeddingModelSignature(getClass().getName(), modelName(), modelName(), dimension(), 1);
    }
    @Override void close();
}
