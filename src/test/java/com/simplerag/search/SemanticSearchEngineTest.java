package com.simplerag.search;

import com.simplerag.adapter.out.onnx.Langchain4jOnnxEmbeddingProvider;
import com.simplerag.model.SearchResult;
import com.simplerag.model.SemanticHighlight;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SemanticSearchEngineTest {
    public static void main(String[] args) throws Exception {
        Path knowledge = Path.of("examples", "knowledge").toAbsolutePath();
        SemanticSearchEngine engine = new SemanticSearchEngine(new Langchain4jOnnxEmbeddingProvider());
        SemanticSearchEngine.IndexReport report = engine.index(List.of(knowledge), null);

        check(report.files() >= 17, "应索引扩展后的示例语料");
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

        List<SearchResult> retryLocation = engine.search("接口超时重试相关代码在哪", 5, "全部");
        check(!retryLocation.isEmpty() && retryLocation.get(0).chunk().fileName().equals("retry-and-errors.py"),
                "代码定位问题应去除泛化问法并优先返回相关代码文件");
        check(retryLocation.get(0).reason().contains("代码") || retryLocation.get(0).reason().contains("路径")
                        || retryLocation.get(0).reason().contains("语义")
                        || retryLocation.get(0).reason().contains("RRF"),
                "代码定位结果应给出可解释的命中原因");

        List<SearchResult> symbolLocation = engine.search("request_with_retry 定义在哪个文件", 5, "全部");
        check(!symbolLocation.isEmpty() && symbolLocation.get(0).chunk().fileName().equals("retry-and-errors.py"),
                "方法定义定位应奖励声明和文件路径信号");

        if (engine.semanticEnabled()) {
            List<SearchResult> navigation = engine.search("怎样收起左边栏给编辑区域更多空间", 5, "全部");
            check(!navigation.isEmpty() && navigation.get(0).chunk().fileName().equals("ui-navigation.md"),
                    "模型应跨语言命中未写入概念词典的界面描述");
            check(navigation.get(0).reason().contains("RRF"),
                    "默认检索应使用 BM25 与向量 RRF 融合并经过重排");
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
        SemanticSearchEngine restoredEngine = new SemanticSearchEngine(new Langchain4jOnnxEmbeddingProvider());
        restoredEngine.restore(restored);
        check(restoredEngine.chunkCount() == engine.chunkCount(), "持久化后片段数应一致");
        check(!restoredEngine.semanticEnabled(), "缺少 manifest 的快照不得恢复为兼容向量索引");
        Files.deleteIfExists(indexFile);

        System.out.println("SemanticSearchEngineTest: all checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
