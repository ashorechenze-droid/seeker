package com.simplerag.application.diagnostics;

import java.util.List;

public interface DiagnosticLog extends DiagnosticSink {
    List<DiagnosticEvent> recentEvents();
}
