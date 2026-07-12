package com.simplerag.application.usecase;

import com.simplerag.application.dto.DocumentReference;
import com.simplerag.application.dto.SearchResultView;
import com.simplerag.application.port.in.SearchKnowledge;
import com.simplerag.application.runtime.ActiveKnowledgeRuntime;
import com.simplerag.model.DocumentChunk;
import com.simplerag.model.SearchResult;
import com.simplerag.search.IndexHandle;
import com.simplerag.model.SemanticHighlight;

import java.io.IOException;
import java.util.List;

public final class SearchUseCase implements SearchKnowledge {
    private final ActiveKnowledgeRuntime runtime;
    public SearchUseCase(ActiveKnowledgeRuntime runtime) { this.runtime = runtime; }
    @Override public List<SearchResultView> search(String knowledgeBaseId, long expectedRevision,
                                                   String query, int limit, String extension) {
        IndexHandle handle = requireIdentity(knowledgeBaseId, expectedRevision);
        return handle.engine().search(query, limit, extension).stream().map(SearchUseCase::toView).toList();
    }
    @Override public List<SemanticHighlight> semanticHighlights(String knowledgeBaseId, long expectedRevision,
                                                                 String query, DocumentReference document, int limit)
            throws IOException {
        IndexHandle handle = requireIdentity(knowledgeBaseId, expectedRevision);
        DocumentChunk chunk = handle.engine().snapshot().chunks().stream()
                .filter(candidate -> candidate.id().equals(document.id())).findFirst()
                .orElseThrow(() -> new StaleTaskException("检索片段已失效，请重新搜索"));
        return handle.engine().semanticHighlights(query, chunk, limit);
    }

    private IndexHandle requireIdentity(String knowledgeBaseId, long revision) {
        IndexHandle handle = runtime.current().indexHandle();
        if (!handle.knowledgeBaseId().equals(knowledgeBaseId) || handle.sourceRevision() != revision) {
            throw new StaleTaskException("知识库或数据版本已变化，任务结果已丢弃");
        }
        return handle;
    }

    private static SearchResultView toView(SearchResult result) {
        DocumentChunk chunk = result.chunk();
        DocumentReference document = new DocumentReference(chunk.id(), chunk.filePath(), chunk.fileName(),
                chunk.extension(), chunk.startLine(), chunk.endLine(), chunk.sourceLocation(),
                chunk.content(), chunk.hasEmbedding());
        return new SearchResultView(document, result.score(), result.reason());
    }
}
