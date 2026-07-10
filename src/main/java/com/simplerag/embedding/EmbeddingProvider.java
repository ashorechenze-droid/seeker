package com.simplerag.embedding;

import java.io.IOException;
import java.util.List;

public interface EmbeddingProvider extends AutoCloseable {
    boolean isConfigured();

    List<float[]> embed(List<String> texts) throws IOException;

    String modelName();

    String status();

    @Override
    void close();
}
