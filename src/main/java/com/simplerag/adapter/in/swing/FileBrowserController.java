package com.simplerag.adapter.in.swing;

import com.simplerag.application.dto.FileContentView;
import com.simplerag.application.dto.FileNodeView;
import com.simplerag.application.port.in.BrowseKnowledgeFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public final class FileBrowserController {
    private final BrowseKnowledgeFiles files;

    public FileBrowserController(BrowseKnowledgeFiles files) {
        this.files = files;
    }

    public List<FileNodeView> roots(KnowledgeController.TaskIdentity identity) {
        return files.rootNodes(identity.knowledgeBaseId(), identity.sourceRevision());
    }

    public List<FileNodeView> children(KnowledgeController.TaskIdentity identity, Path directory)
            throws IOException {
        return files.children(identity.knowledgeBaseId(), identity.sourceRevision(), directory);
    }

    public FileContentView read(KnowledgeController.TaskIdentity identity, Path file) throws IOException {
        return files.readFile(identity.knowledgeBaseId(), identity.sourceRevision(), file);
    }
}
