package com.simplerag.application.port.out;

public interface FreshnessRepository {
    boolean markIndexDirtyIfCurrent(String knowledgeBaseId, long sourceRevision, String reason,
                                    String observedSourceHash, Long verifiedAt);
    void recordSourceVerification(String knowledgeBaseId, long sourceRevision, String sourceHash, long verifiedAt);
}
