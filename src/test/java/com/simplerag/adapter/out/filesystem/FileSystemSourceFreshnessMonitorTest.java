package com.simplerag.adapter.out.filesystem;

import com.simplerag.application.freshness.FreshnessState;
import com.simplerag.application.freshness.SourceFingerprint;
import com.simplerag.application.port.out.SourceFreshnessMonitor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemSourceFreshnessMonitorTest {
    @TempDir Path temporaryDirectory;

    @Test
    void detectsModificationWithoutRestart() throws Exception {
        Path source = source("modify");
        Path file = source.resolve("note.txt");
        Files.writeString(file, "original");
        FileTime originalTime = Files.getLastModifiedTime(file);
        CountDownLatch invalidated = new CountDownLatch(1);
        try (FileSystemSourceFreshnessMonitor monitor = monitor()) {
            monitor.start(request(source), listener(invalidated));
            assertEquals(FreshnessState.VERIFIED, monitor.snapshot().state());

            Files.writeString(file, "modified");
            Files.setLastModifiedTime(file, originalTime);

            assertTrue(invalidated.await(5, TimeUnit.SECONDS));
            assertEquals(FreshnessState.CHANGED, monitor.snapshot().state());
        }
    }

    @Test
    void registersNewSubdirectoriesRecursively() throws Exception {
        Path source = source("nested");
        CountDownLatch invalidated = new CountDownLatch(1);
        try (FileSystemSourceFreshnessMonitor monitor = monitor()) {
            monitor.start(request(source), listener(invalidated));
            long reconciliations = monitor.snapshot().reconciliationCount();
            Path nested = Files.createDirectories(source.resolve("created-later").resolve("deep"));
            await(() -> monitor.snapshot().reconciliationCount() > reconciliations, Duration.ofSeconds(5));
            assertEquals(FreshnessState.VERIFIED, monitor.snapshot().state());

            Files.writeString(nested.resolve("secret.txt"), "new nested content");

            await(() -> monitor.snapshot().state() == FreshnessState.CHANGED, Duration.ofSeconds(5));
            assertEquals(FreshnessState.CHANGED, monitor.snapshot().state());
        }
    }

    @Test
    void overflowForcesFullReconciliation() throws Exception {
        Path source = source("overflow");
        try (FileSystemSourceFreshnessMonitor monitor = monitor()) {
            monitor.start(request(source), listener(new CountDownLatch(1)));
            long before = monitor.snapshot().reconciliationCount();

            monitor.simulateOverflowForTesting();

            await(() -> monitor.snapshot().reconciliationCount() > before, Duration.ofSeconds(5));
            assertEquals(FreshnessState.VERIFIED, monitor.snapshot().state());
        }
    }

    @Test
    void periodicReconciliationDetectsChangesWhenNoWatcherEventArrives() throws Exception {
        Path source = source("periodic");
        SourceFingerprint baseline = SourceFingerprint.capture(List.of(source));
        AtomicInteger calls = new AtomicInteger();
        FreshnessReconciler reconciler = new FreshnessReconciler() {
            @Override public SourceFingerprint reconcile(List<Path> sources) throws java.io.IOException {
                if (calls.incrementAndGet() == 1) return baseline;
                return new SourceFingerprint("changed-without-watch-event", System.currentTimeMillis());
            }
        };
        CountDownLatch invalidated = new CountDownLatch(1);
        try (FileSystemSourceFreshnessMonitor monitor = new FileSystemSourceFreshnessMonitor(reconciler,
                Duration.ofMillis(50), Duration.ofMillis(100))) {
            monitor.start(new SourceFreshnessMonitor.MonitorRequest("kb", 1, List.of(source), baseline.hash()),
                    listener(invalidated));

            assertTrue(invalidated.await(5, TimeUnit.SECONDS));
            assertEquals(FreshnessState.CHANGED, monitor.snapshot().state());
            assertTrue(monitor.snapshot().reconciliationCount() >= 2);
        }
    }

    @Test
    void inaccessibleRootAndClosedWatcherAreNeverReportedFresh() throws Exception {
        Path missing = temporaryDirectory.resolve("missing");
        FileSystemSourceFreshnessMonitor monitor = monitor();
        CountDownLatch invalidated = new CountDownLatch(1);
        monitor.start(new SourceFreshnessMonitor.MonitorRequest("kb", 1, List.of(missing), "missing"),
                listener(invalidated));

        assertTrue(invalidated.await(2, TimeUnit.SECONDS));
        assertEquals(FreshnessState.UNAVAILABLE, monitor.snapshot().state());

        monitor.close();
        assertEquals(FreshnessState.STOPPED, monitor.snapshot().state());
    }

    private Path source(String name) throws Exception {
        return Files.createDirectories(temporaryDirectory.resolve(name));
    }

    private SourceFreshnessMonitor.MonitorRequest request(Path source) {
        return new SourceFreshnessMonitor.MonitorRequest("kb", 1, List.of(source),
                SourceFingerprint.capture(List.of(source)).hash());
    }

    private FileSystemSourceFreshnessMonitor monitor() {
        return new FileSystemSourceFreshnessMonitor(new FreshnessReconciler(),
                Duration.ofMillis(50), Duration.ofMillis(150));
    }

    private static SourceFreshnessMonitor.Listener listener(CountDownLatch invalidated) {
        return new SourceFreshnessMonitor.Listener() {
            @Override public void sourceVerified(SourceFreshnessMonitor.MonitorRequest request,
                                                 SourceFingerprint fingerprint) { }
            @Override public void sourceInvalidated(SourceFreshnessMonitor.MonitorRequest request,
                                                    SourceFingerprint fingerprint, String reason) {
                invalidated.countDown();
            }
        };
    }

    private static void await(java.util.function.BooleanSupplier condition, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "condition was not met within " + timeout);
    }
}
