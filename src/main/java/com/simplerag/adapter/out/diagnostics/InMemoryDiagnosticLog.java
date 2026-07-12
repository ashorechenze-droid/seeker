package com.simplerag.adapter.out.diagnostics;

import com.simplerag.application.diagnostics.DiagnosticEvent;
import com.simplerag.application.diagnostics.DiagnosticLog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Bounded process-local event log; it never persists prompts, keys or chunk text. */
public final class InMemoryDiagnosticLog implements DiagnosticLog {
    private final int capacity;
    private final ArrayDeque<DiagnosticEvent> events = new ArrayDeque<>();

    public InMemoryDiagnosticLog() { this(300); }

    public InMemoryDiagnosticLog(int capacity) {
        this.capacity = Math.max(20, capacity);
    }

    @Override
    public synchronized void record(String type, String category, String message,
                                    Map<String, String> attributes) {
        while (events.size() >= capacity) events.removeFirst();
        events.addLast(new DiagnosticEvent(System.currentTimeMillis(), type, category, message, attributes));
    }

    @Override
    public synchronized List<DiagnosticEvent> recentEvents() {
        return List.copyOf(new ArrayList<>(events));
    }
}
