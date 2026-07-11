package com.simplerag.application.port.out;

import com.simplerag.application.freshness.FreshnessSnapshot;
import com.simplerag.application.freshness.SourceFingerprint;

import java.nio.file.Path;
import java.util.List;

public interface SourceFreshnessMonitor extends AutoCloseable {
    void start(MonitorRequest request, Listener listener);

    void reconcileNow();

    FreshnessSnapshot snapshot();

    @Override
    void close();

    record MonitorRequest(String knowledgeBaseId, long sourceRevision, List<Path> sources,
                          String expectedSourceHash) {
        public MonitorRequest {
            sources = List.copyOf(sources);
        }
    }

    interface Listener {
        void sourceVerified(MonitorRequest request, SourceFingerprint fingerprint);

        void sourceInvalidated(MonitorRequest request, SourceFingerprint fingerprint, String reason);
    }
}
