package com.simplerag.adapter.out.filesystem;

import com.simplerag.application.freshness.FreshnessSnapshot;
import com.simplerag.application.freshness.FreshnessState;
import com.simplerag.application.freshness.SourceFingerprint;
import com.simplerag.application.port.out.SourceFreshnessMonitor;
import com.simplerag.search.IndexIdentity;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class FileSystemSourceFreshnessMonitor implements SourceFreshnessMonitor {
    private final Object lock = new Object();
    private final FreshnessReconciler reconciler;
    private final long debounceMillis;
    private final long reconciliationMillis;
    private volatile FreshnessSnapshot snapshot = FreshnessSnapshot.stopped("源文件监控未启动");
    private Session current;

    public FileSystemSourceFreshnessMonitor() {
        this(new FreshnessReconciler(), Duration.ofMillis(250), Duration.ofSeconds(30));
    }

    public FileSystemSourceFreshnessMonitor(FreshnessReconciler reconciler, Duration debounce,
                                            Duration reconciliationInterval) {
        this.reconciler = reconciler;
        this.debounceMillis = Math.max(0, debounce.toMillis());
        this.reconciliationMillis = Math.max(50, reconciliationInterval.toMillis());
    }

    @Override
    public void start(MonitorRequest request, Listener listener) {
        Session session;
        try {
            session = new Session(request, listener, FileSystems.getDefault().newWatchService(),
                    Executors.newScheduledThreadPool(2, daemonFactory("simplerag-freshness")));
            synchronized (lock) {
                stopCurrent(false);
                current = session;
                snapshot = new FreshnessSnapshot(request.knowledgeBaseId(), request.sourceRevision(),
                        FreshnessState.VERIFYING, 0, "", 0, "正在核对源文件", 0);
            }
            for (Path source : request.sources()) registerRecursively(session, source.toAbsolutePath().normalize());
            session.watchThread = daemonFactory("simplerag-watch").newThread(() -> watchLoop(session));
            session.watchThread.start();
            session.scheduler.scheduleAtFixedRate(() -> reconcile(session), reconciliationMillis,
                    reconciliationMillis, TimeUnit.MILLISECONDS);
            reconcile(session);
        } catch (IOException failure) {
            unavailable(request, listener, failure.getMessage());
        }
    }

    @Override
    public void reconcileNow() {
        Session session;
        synchronized (lock) {
            session = current;
        }
        if (session != null) reconcile(session);
    }

    @Override
    public FreshnessSnapshot snapshot() {
        return snapshot;
    }

    @Override
    public void close() {
        synchronized (lock) {
            stopCurrent(true);
        }
    }

    void simulateOverflowForTesting() {
        Session session;
        synchronized (lock) {
            session = current;
        }
        if (session != null) sourceEvent(session, "文件监听事件溢出，正在完整核对", true, false);
    }

    private void watchLoop(Session session) {
        try {
            while (isCurrent(session)) {
                WatchKey key = session.watchService.take();
                Path directory;
                synchronized (lock) {
                    directory = session.directories.get(key);
                }
                if (directory == null) {
                    key.reset();
                    continue;
                }
                boolean changed = false;
                boolean overflow = false;
                boolean definitelyChanged = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        overflow = true;
                        continue;
                    }
                    Path path = directory.resolve((Path) event.context()).toAbsolutePath().normalize();
                    if (ignored(session, path)) continue;
                    changed = true;
                    boolean createdDirectory = event.kind() == StandardWatchEventKinds.ENTRY_CREATE
                            && Files.isDirectory(path);
                    if (createdDirectory) {
                        try {
                            registerRecursively(session, path);
                        } catch (IOException failure) {
                            markUnavailable(session, "无法监控新建目录: " + path);
                        }
                    } else if (!(event.kind() == StandardWatchEventKinds.ENTRY_MODIFY
                            && Files.isDirectory(path))) {
                        definitelyChanged = true;
                    }
                }
                if (!key.reset()) markUnavailable(session, "源文件监控已中断: " + directory);
                if (overflow) sourceEvent(session, "文件监听事件溢出，正在完整核对", true, false);
                else if (changed) sourceEvent(session, "检测到源文件变化，需要重建索引", false,
                        definitelyChanged);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ClosedWatchServiceException ignored) {
        } catch (RuntimeException failure) {
            markUnavailable(session, "源文件监控异常: " + failure.getMessage());
        }
    }

    private void sourceEvent(Session session, String reason, boolean immediate, boolean definitelyChanged) {
        Listener listener = null;
        synchronized (lock) {
            if (current != session) return;
            session.generation++;
            if (definitelyChanged) session.invalidated = true;
            snapshot = new FreshnessSnapshot(session.request.knowledgeBaseId(), session.request.sourceRevision(),
                    definitelyChanged ? FreshnessState.CHANGED : FreshnessState.VERIFYING,
                    session.generation, snapshot.sourceHash(), snapshot.verifiedAt(),
                    reason, session.reconciliationCount);
            if (session.pending != null) session.pending.cancel(false);
            session.pending = session.scheduler.schedule(() -> reconcile(session), immediate ? 0 : debounceMillis,
                    TimeUnit.MILLISECONDS);
            if (definitelyChanged) listener = session.listener;
        }
        if (listener != null) listener.sourceInvalidated(session.request, null, reason);
    }

    private void reconcile(Session session) {
        long generation;
        synchronized (lock) {
            if (current != session) return;
            generation = session.generation;
        }
        try {
            SourceFingerprint fingerprint = reconciler.reconcile(session.request.sources());
            Listener listener;
            boolean invalid;
            synchronized (lock) {
                if (current != session) return;
                if (session.generation != generation) {
                    sourceEvent(session, "核对期间又检测到变化，正在重新核对", false, false);
                    return;
                }
                session.reconciliationCount++;
                invalid = session.invalidated
                        || !session.request.expectedSourceHash().equals(fingerprint.hash());
                if (invalid) session.invalidated = true;
                snapshot = new FreshnessSnapshot(session.request.knowledgeBaseId(), session.request.sourceRevision(),
                        invalid ? FreshnessState.CHANGED : FreshnessState.VERIFIED, session.generation,
                        fingerprint.hash(), fingerprint.verifiedAt(),
                        invalid ? "源文件已变化，需要重建索引" : "源文件状态已核对",
                        session.reconciliationCount);
                listener = session.listener;
            }
            if (invalid) listener.sourceInvalidated(session.request, fingerprint, snapshot.reason());
            else listener.sourceVerified(session.request, fingerprint);
        } catch (IOException failure) {
            markUnavailable(session, failure.getMessage());
        }
    }

    private void registerRecursively(Session session, Path root) throws IOException {
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new IOException("数据源目录不可访问: " + root);
        }
        try (var paths = Files.walk(root)) {
            for (Path directory : paths.filter(Files::isDirectory)
                    .filter(path -> !IndexIdentity.isIgnored(root, path)).toList()) {
                WatchKey key = directory.register(session.watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                synchronized (lock) {
                    if (current != session) {
                        key.cancel();
                        return;
                    }
                    session.directories.put(key, directory);
                }
            }
        }
    }

    private boolean ignored(Session session, Path path) {
        for (Path source : session.request.sources()) {
            Path root = source.toAbsolutePath().normalize();
            if (path.startsWith(root) && IndexIdentity.isIgnored(root, path)) return true;
        }
        return false;
    }

    private void markUnavailable(Session session, String reason) {
        Listener listener;
        synchronized (lock) {
            if (current != session) return;
            snapshot = new FreshnessSnapshot(session.request.knowledgeBaseId(), session.request.sourceRevision(),
                    FreshnessState.UNAVAILABLE, session.generation, snapshot.sourceHash(), snapshot.verifiedAt(),
                    reason == null ? "无法证明源文件状态" : reason, session.reconciliationCount);
            listener = session.listener;
        }
        listener.sourceInvalidated(session.request, null, snapshot.reason());
    }

    private void unavailable(MonitorRequest request, Listener listener, String reason) {
        synchronized (lock) {
            stopCurrent(false);
            snapshot = new FreshnessSnapshot(request.knowledgeBaseId(), request.sourceRevision(),
                    FreshnessState.UNAVAILABLE, 0, "", 0,
                    reason == null ? "无法启动源文件监控" : reason, 0);
        }
        listener.sourceInvalidated(request, null, snapshot.reason());
    }

    private boolean isCurrent(Session session) {
        synchronized (lock) {
            return current == session;
        }
    }

    private void stopCurrent(boolean publishStoppedState) {
        Session session = current;
        current = null;
        if (session != null) {
            if (session.pending != null) session.pending.cancel(false);
            session.scheduler.shutdownNow();
            try {
                session.watchService.close();
            } catch (IOException ignored) {
            }
            if (session.watchThread != null) session.watchThread.interrupt();
        }
        if (publishStoppedState) snapshot = FreshnessSnapshot.stopped("源文件监控已关闭");
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return task -> {
            Thread thread = new Thread(task, prefix + "-" + System.nanoTime());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static final class Session {
        private final MonitorRequest request;
        private final Listener listener;
        private final WatchService watchService;
        private final ScheduledExecutorService scheduler;
        private final Map<WatchKey, Path> directories = new HashMap<>();
        private long generation;
        private long reconciliationCount;
        private boolean invalidated;
        private ScheduledFuture<?> pending;
        private Thread watchThread;

        private Session(MonitorRequest request, Listener listener, WatchService watchService,
                        ScheduledExecutorService scheduler) {
            this.request = request;
            this.listener = listener;
            this.watchService = watchService;
            this.scheduler = scheduler;
        }
    }
}
