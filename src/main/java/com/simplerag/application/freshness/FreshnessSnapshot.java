package com.simplerag.application.freshness;

public record FreshnessSnapshot(
        String knowledgeBaseId,
        long sourceRevision,
        FreshnessState state,
        long generation,
        String sourceHash,
        long verifiedAt,
        String reason,
        long reconciliationCount
) {
    public static FreshnessSnapshot stopped(String reason) {
        return new FreshnessSnapshot("", -1, FreshnessState.STOPPED, 0, "", 0,
                reason == null ? "源文件监控未启动" : reason, 0);
    }

    public boolean provesFresh(String expectedKnowledgeBaseId, long expectedSourceRevision) {
        return state == FreshnessState.VERIFIED
                && knowledgeBaseId.equals(expectedKnowledgeBaseId)
                && sourceRevision == expectedSourceRevision;
    }
}
