package com.simplerag.search;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentScannerTest {
    @TempDir Path temp;

    private static final ScanBudget GENEROUS = new ScanBudget(10_000, 1L << 40, 32);

    private static DocumentScanner scanner() {
        return new DocumentScanner(new SensitiveFilePolicy(true), GENEROUS);
    }

    @Test
    void appliesTraversalPolicyWithoutReadingOrChunkingDocuments() throws Exception {
        Path root = Files.createDirectories(temp.resolve("knowledge"));
        Files.writeString(root.resolve("notes.md"), "included");
        Files.writeString(root.resolve("README"), "included without extension");
        Files.writeString(root.resolve("photo.png"), "ignored");
        Files.write(root.resolve("too-large.txt"), new byte[2 * 1024 * 1024 + 1]);
        Path ignored = Files.createDirectories(root.resolve("node_modules"));
        Files.writeString(ignored.resolve("hidden.md"), "ignored directory");

        DocumentScanner.ScanResult result = scanner().scan(List.of(root));

        assertEquals(List.of(root.toAbsolutePath().normalize()), result.roots());
        assertEquals(2, result.documents().size());
        assertTrue(result.documents().stream().allMatch(item -> item.root().equals(root.toAbsolutePath().normalize())));
        assertEquals(1, result.warnings().stream().filter(IndexBuildWarning::skipped).count());
        assertTrue(warning(result, "文件超过 reader 大小限制").isPresent());
    }

    @Test
    void credentialFilesAndDirectoriesNeverEnterTheIndex() throws Exception {
        Path root = Files.createDirectories(temp.resolve("kb"));
        Files.writeString(root.resolve("notes.md"), "普通知识文档，应当被索引");
        Files.writeString(root.resolve(".env"), "OPENAI_API_KEY=sk-live-secret");
        Files.writeString(root.resolve(".env.production"), "DB_PASSWORD=hunter2");
        Files.writeString(root.resolve("server.pem"), "-----BEGIN PRIVATE KEY-----");
        Files.writeString(root.resolve("secrets.yaml"), "token: abc");
        Path ssh = Files.createDirectories(root.resolve(".ssh"));
        Files.writeString(ssh.resolve("config.txt"), "Host example.com");

        DocumentScanner.ScanResult result = scanner().scan(List.of(root));

        assertEquals(List.of("notes.md"), result.documents().stream()
                .map(item -> item.path().getFileName().toString()).toList());
        List<IndexBuildWarning> sensitive = result.warnings().stream()
                .filter(warning -> SensitiveFilePolicy.REASON_ID.equals(warning.readerId())).toList();
        assertEquals(5, sensitive.size(), "四个凭据文件加一个凭据目录都应逐条上报");
        assertTrue(sensitive.stream().allMatch(IndexBuildWarning::skipped),
                "策略跳过属于本可索引却被拒绝，必须计入跳过数");
    }

    @Test
    void credentialFilesAreIndexedWhenThePolicyIsDisabled() throws Exception {
        Path root = Files.createDirectories(temp.resolve("kb"));
        Files.writeString(root.resolve("app.properties"), "key=value");
        Files.writeString(root.resolve(".env"), "OPENAI_API_KEY=sk-live-secret");

        DocumentScanner.ScanResult result =
                new DocumentScanner(new SensitiveFilePolicy(false), GENEROUS).scan(List.of(root));

        assertTrue(result.warnings().stream()
                .noneMatch(warning -> SensitiveFilePolicy.REASON_ID.equals(warning.readerId())));
        assertEquals(List.of("app.properties"), result.documents().stream()
                .map(item -> item.path().getFileName().toString()).toList());
    }

    @Test
    void nestedSourceRootsAreCollapsedSoNoFileIsIndexedTwice() throws Exception {
        Path outer = Files.createDirectories(temp.resolve("outer"));
        Path inner = Files.createDirectories(outer.resolve("inner"));
        Files.writeString(inner.resolve("shared.md"), "同一个文件不能因为两个数据源被索引两次");

        DocumentScanner.ScanResult result = scanner().scan(List.of(outer, inner));

        assertEquals(List.of(outer.toAbsolutePath().normalize()), result.roots());
        assertEquals(1, result.documents().size());
    }

    @Test
    void traversalStopsAtTheFileBudgetAndSaysSo() throws Exception {
        Path root = Files.createDirectories(temp.resolve("huge"));
        for (int index = 0; index < 5; index++) {
            Files.writeString(root.resolve("note-" + index + ".md"), "内容 " + index);
        }

        DocumentScanner.ScanResult result = new DocumentScanner(new SensitiveFilePolicy(true),
                new ScanBudget(2, 1L << 40, 32)).scan(List.of(root));

        assertEquals(2, result.documents().size());
        IndexBuildWarning budget = warning(result, "扫描文件数已达上限").orElseThrow();
        assertTrue(budget.skipped(), "索引不完整必须可见，不能静默截断");
    }

    @Test
    void unsupportedFormatsAreAggregatedIntoOneInformationalWarning() throws Exception {
        Path root = Files.createDirectories(temp.resolve("mixed"));
        Files.writeString(root.resolve("notes.md"), "唯一可索引的文档");
        Files.writeString(root.resolve("a.png"), "binary");
        Files.writeString(root.resolve("b.png"), "binary");
        Files.writeString(root.resolve("c.jpg"), "binary");

        DocumentScanner.ScanResult result = scanner().scan(List.of(root));

        IndexBuildWarning unsupported = warning(result, "不支持格式").orElseThrow();
        assertTrue(unsupported.message().contains("3 个"));
        assertTrue(unsupported.message().contains("png 2"));
        assertFalse(unsupported.skipped(), "从不可索引的格式不应污染跳过计数");
        assertEquals(1, result.warnings().size());
    }

    @Test
    void traversalStopsAtTheDepthBudgetAndSaysSo() throws Exception {
        Path root = Files.createDirectories(temp.resolve("deep"));
        Path nested = Files.createDirectories(root.resolve("a").resolve("b").resolve("c"));
        Files.writeString(nested.resolve("note.md"), "这个文件位于最大深度之外");
        Files.writeString(root.resolve("top.md"), "根目录下的文件仍然应当被索引");

        DocumentScanner.ScanResult result = new DocumentScanner(new SensitiveFilePolicy(true),
                new ScanBudget(10_000, 1L << 40, 2)).scan(List.of(root));

        assertEquals(List.of("top.md"), result.documents().stream()
                .map(item -> item.path().getFileName().toString()).toList());
        assertTrue(warning(result, "最大深度").isPresent(), "深度截断必须可见");
    }

    private static java.util.Optional<IndexBuildWarning> warning(DocumentScanner.ScanResult result,
                                                                 String fragment) {
        return result.warnings().stream().filter(item -> item.message().contains(fragment)).findFirst();
    }
}
