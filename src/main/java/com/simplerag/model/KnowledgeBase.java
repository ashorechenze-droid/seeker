package com.simplerag.model;

public record KnowledgeBase(String id, String name, String description, long createdAt, long updatedAt) {
    @Override
    public String toString() {
        return name;
    }
}
