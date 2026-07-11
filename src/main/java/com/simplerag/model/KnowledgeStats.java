package com.simplerag.model;

import java.nio.file.Path;

public record KnowledgeStats(int files, int chunks, long indexedAt, Path indexPath) {
}
