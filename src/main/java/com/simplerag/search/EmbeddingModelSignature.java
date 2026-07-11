package com.simplerag.search;

import java.io.Serial;
import java.io.Serializable;

public record EmbeddingModelSignature(
        String providerType,
        String modelName,
        String modelFileSignature,
        int dimension,
        int preprocessingVersion
) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    public String value() {
        return providerType + ":" + modelName + ":" + modelFileSignature + ":"
                + dimension + ":" + preprocessingVersion;
    }
}
