package com.simplerag.search;

import com.simplerag.model.SearchResult;
import com.simplerag.model.SemanticHighlight;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SemanticSearchEngineTest {
    public static void main(String[] args) throws Exception {
        Path knowledge = Path.of("examples", "knowledge").toAbsolutePath();
        SemanticSearchEngine engine = new SemanticSearchEngine();
        SemanticSearchEngine.IndexReport report = engine.index(List.of(knowledge), null);

        check(report.files() == 5, "应索引 5 个示例文件");
        check(report.chunks() >= 4, "每个文件至少生成一个片段");

        List<SearchResult> database = engine.search("如何连数据库", 5, "全部");
        check(!database.isEmpty(), "数据库语义查询应有结果");
        check(database.get(0).chunk().fileName().equals("database-connection.md"),
                "中文查询应命中英文 MySQL Connection 笔记");

        List<SearchResult> auth = engine.search("how to validate login token", 5, "全部");
        check(!auth.isEmpty() && auth.get(0).chunk().fileName().equals("authentication.md"),
                "英文查询应命中中英混合的认证笔记");

        List<SearchResult> retry = engine.search("接口失败后怎么重试", 5, "py");
        check(!retry.isEmpty() && retry.get(0).chunk().fileName().equals("retry-and-errors.py"),
                "文件类型过滤和错误处理概念应共同生效");

        if (engine.semanticEnabled()) {
            List<SearchResult> navigation = engine.search("怎样收起左边栏给编辑区域更多空间", 5, "全部");
            check(!navigation.isEmpty() && navigation.get(0).chunk().fileName().equals("ui-navigation.md"),
                    "模型应跨语言命中未写入概念词典的界面描述");
            check(navigation.get(0).reason().equals("向量语义匹配"),
                    "跨语言结果必须来自真实向量语义评分");
            List<SemanticHighlight> highlights = engine.semanticHighlights(
                    "怎样收起左边栏给编辑区域更多空间", navigation.get(0).chunk(), 2);
            check(!highlights.isEmpty(), "向量结果应能定位到具体语义片段");
            String highlightedText = highlights.stream()
                    .map(hit -> navigation.get(0).chunk().content().substring(hit.startOffset(), hit.endOffset()))
                    .reduce("", (left, right) -> left + " " + right).toLowerCase();
            check(highlightedText.contains("sidebar") || highlightedText.contains("editor"),
                    "语义高亮应覆盖英文侧边栏描述");
            System.out.println("Vector semantic check: enabled and cross-language match passed");
        }

        Path indexFile = Path.of("target", "semantic-search-test", "index.bin");
        IndexStore store = new IndexStore(indexFile);
        store.save(engine.snapshot());
        IndexSnapshot restored = store.load().orElseThrow();
        SemanticSearchEngine restoredEngine = new SemanticSearchEngine();
        restoredEngine.restore(restored);
        check(restoredEngine.chunkCount() == engine.chunkCount(), "持久化后片段数应一致");
        Files.deleteIfExists(indexFile);

        System.out.println("SemanticSearchEngineTest: all checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
