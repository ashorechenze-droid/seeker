package com.simplerag.application.dto;

import java.nio.file.Path;

public record IndexBuildProgress(int processed, int total, Path currentFile, String stage) {
}
