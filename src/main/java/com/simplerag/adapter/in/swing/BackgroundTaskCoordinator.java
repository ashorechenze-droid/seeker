package com.simplerag.adapter.in.swing;

import javax.swing.SwingWorker;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Owns Swing background workers, cancellation, identity checks and EDT completion callbacks. */
public final class BackgroundTaskCoordinator {
    public interface TaskHandle {
        void cancel();
        boolean isDone();
    }

    @FunctionalInterface
    public interface BackgroundCommand<R, P> {
        R run(Consumer<P> publish) throws Exception;
    }

    public <R, P> TaskHandle submit(Object capturedIdentity, Supplier<?> currentIdentity,
                                    BackgroundCommand<R, P> command, Consumer<List<P>> onProgress,
                                    Consumer<R> onSuccess, Consumer<Throwable> onFailure,
                                    Runnable onDiscarded) {
        Objects.requireNonNull(command, "command");
        SwingWorker<R, P> worker = new SwingWorker<>() {
            @Override
            protected R doInBackground() throws Exception {
                return command.run(this::publish);
            }

            @Override
            protected void process(List<P> chunks) {
                if (!isCancelled() && onProgress != null) onProgress.accept(chunks);
            }

            @Override
            protected void done() {
                if (isCancelled() || !identityMatches(capturedIdentity, currentIdentity)) {
                    if (onDiscarded != null) onDiscarded.run();
                    return;
                }
                try {
                    R result = get();
                    if (onSuccess != null) onSuccess.accept(result);
                } catch (CancellationException ignored) {
                    if (onDiscarded != null) onDiscarded.run();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    if (onFailure != null) onFailure.accept(interrupted);
                } catch (ExecutionException failure) {
                    if (onFailure != null) onFailure.accept(rootCause(failure));
                }
            }
        };
        worker.execute();
        return new TaskHandle() {
            @Override public void cancel() { worker.cancel(true); }
            @Override public boolean isDone() { return worker.isDone(); }
        };
    }

    private static boolean identityMatches(Object captured, Supplier<?> current) {
        return captured == null || current == null || Objects.equals(captured, current.get());
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current;
    }
}
