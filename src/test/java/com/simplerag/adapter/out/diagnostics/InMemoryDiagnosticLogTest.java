package com.simplerag.adapter.out.diagnostics;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryDiagnosticLogTest {
    @Test void boundsEventsAndRedactsCredentialLikeMessages() {
        InMemoryDiagnosticLog log = new InMemoryDiagnosticLog(20);
        for (int i = 0; i < 25; i++) log.record("adapter latency", "api",
                "Authorization: Bearer secret-" + i, Map.of("latencyMs", Integer.toString(i)));
        assertEquals(20, log.recentEvents().size());
        assertTrue(log.recentEvents().stream().noneMatch(event -> event.message().contains("secret-")));
    }
}
