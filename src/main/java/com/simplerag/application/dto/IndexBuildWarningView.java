package com.simplerag.application.dto;

import java.nio.file.Path;

public record IndexBuildWarningView(Path path, String readerId, String message, boolean skipped) { }
