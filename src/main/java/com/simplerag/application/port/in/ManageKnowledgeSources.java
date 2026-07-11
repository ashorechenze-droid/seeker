package com.simplerag.application.port.in;

import java.nio.file.Path;
import java.util.List;

public interface ManageKnowledgeSources {
    void addSource(Path path);
    void removeSource(Path path);
    List<Path> roots();
}
