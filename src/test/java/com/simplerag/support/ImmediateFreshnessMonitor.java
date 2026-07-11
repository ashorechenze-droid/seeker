package com.simplerag.support;

import com.simplerag.application.freshness.FreshnessSnapshot;
import com.simplerag.application.freshness.FreshnessState;
import com.simplerag.application.freshness.SourceFingerprint;
import com.simplerag.application.port.out.SourceFreshnessMonitor;

public final class ImmediateFreshnessMonitor implements SourceFreshnessMonitor {
    private FreshnessSnapshot snapshot = FreshnessSnapshot.stopped("test monitor stopped");
    private MonitorRequest request;
    private Listener listener;

    @Override
    public void start(MonitorRequest request, Listener listener) {
        this.request = request;
        this.listener = listener;
        SourceFingerprint fingerprint = SourceFingerprint.capture(request.sources());
        snapshot = new FreshnessSnapshot(request.knowledgeBaseId(), request.sourceRevision(),
                FreshnessState.VERIFIED, 0, fingerprint.hash(), fingerprint.verifiedAt(),
                "源文件状态已核对", 1);
        listener.sourceVerified(request, fingerprint);
    }

    @Override
    public void reconcileNow() {
        if (request != null) start(request, listener);
    }

    @Override
    public FreshnessSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public void close() {
        snapshot = FreshnessSnapshot.stopped("test monitor closed");
    }
}
