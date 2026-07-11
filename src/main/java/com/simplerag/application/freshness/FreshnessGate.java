package com.simplerag.application.freshness;

import com.simplerag.application.port.out.SourceFreshnessMonitor;

public final class FreshnessGate {
    private final SourceFreshnessMonitor monitor;

    public FreshnessGate(SourceFreshnessMonitor monitor) {
        this.monitor = monitor;
    }

    public FreshnessSnapshot requireFresh(String knowledgeBaseId, long sourceRevision) {
        FreshnessSnapshot snapshot = monitor.snapshot();
        if (!snapshot.provesFresh(knowledgeBaseId, sourceRevision)) {
            String reason = snapshot.reason() == null || snapshot.reason().isBlank()
                    ? "源文件状态尚未核对" : snapshot.reason();
            throw new IllegalStateException(reason + "，已禁止发送远程 RAG 请求");
        }
        return snapshot;
    }
}
