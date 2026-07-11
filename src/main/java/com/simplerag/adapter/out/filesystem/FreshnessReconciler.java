package com.simplerag.adapter.out.filesystem;

import com.simplerag.application.freshness.SourceFingerprint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class FreshnessReconciler {
    public SourceFingerprint reconcile(List<Path> sources) throws IOException {
        for (Path source : sources) {
            Path normalized = source.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalized) || !Files.isReadable(normalized)) {
                throw new IOException("数据源目录不可访问: " + normalized);
            }
        }
        try {
            return SourceFingerprint.capture(sources);
        } catch (RuntimeException failure) {
            throw new IOException("无法核对源文件状态", failure);
        }
    }
}
