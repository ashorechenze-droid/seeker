package com.simplerag.model;

public record KnowledgeBase(String id, String name, String description, long createdAt, long updatedAt,
                            long sourceRevision, Long publishedIndexRevision, IndexStatus indexStatus,
                            String lastIndexError, String lastVerifiedSourceHash, Long lastVerifiedAt,
                            String freshnessReason) {
    @Override
    public String toString() {
        return name;
    }
}
