package com.simplerag.application.dto;

import java.util.List;

public record IndexBuildResult(int files, int chunks, int skipped, List<IndexBuildWarningView> warnings) {
    public IndexBuildResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public IndexBuildResult(int files, int chunks, int skipped) {
        this(files, chunks, skipped, List.of());
    }
}
