package com.simplerag.search;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Strategy for extracting source-addressable text from one or more file formats. */
public interface DocumentReader {
    String id();
    int version();
    Set<String> extensions();
    default Set<String> fileNames() { return Set.of(); }
    long maxFileSizeBytes();
    ReadDocument read(Path file, Path root) throws IOException;
}
