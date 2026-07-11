package com.simplerag.adapter.in.swing;

import java.io.IOException;
import java.nio.file.Path;

/** Isolates desktop integration from page and window code. */
public interface DesktopFileGateway {
    void open(Path path) throws IOException;
}
