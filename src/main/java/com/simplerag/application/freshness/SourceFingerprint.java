package com.simplerag.application.freshness;

import com.simplerag.search.IndexIdentity;

import java.nio.file.Path;
import java.util.List;

public record SourceFingerprint(String hash, long verifiedAt) {
    public static SourceFingerprint capture(List<Path> sources) {
        return new SourceFingerprint(IndexIdentity.sourceSetHash(sources), System.currentTimeMillis());
    }
}
