package com.simplerag.search;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Filesystem traversal policy; independent from reading, chunking and ranking. */
public final class DocumentScanner {
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", ".simplerag", "node_modules", "target", "build", "dist",
            "out", "vendor", ".venv", "venv", "__pycache__", ".next", ".gradle"
    );
    private static final int MAX_REPORTED_EXTENSIONS = 6;
    private static final String NO_EXTENSION = "无扩展名";

    private final SensitiveFilePolicy sensitiveFiles;
    private final ScanBudget budget;

    public DocumentScanner() {
        this(new SensitiveFilePolicy(), ScanBudget.fromSystemProperties());
    }

    public DocumentScanner(SensitiveFilePolicy sensitiveFiles, ScanBudget budget) {
        this.sensitiveFiles = sensitiveFiles == null ? new SensitiveFilePolicy() : sensitiveFiles;
        this.budget = budget == null ? ScanBudget.fromSystemProperties() : budget;
    }

    public ScanResult scan(List<Path> sourceRoots) throws IOException {
        return scan(sourceRoots, new DocumentReaderRegistry());
    }

    public ScanResult scan(List<Path> sourceRoots, DocumentReaderRegistry readers) throws IOException {
        List<Path> roots = outermostRoots(sourceRoots);
        List<ScannedDocument> documents = new ArrayList<>();
        List<IndexBuildWarning> warnings = new ArrayList<>();
        ScanBudget.Tracker tracker = budget.tracker();
        Map<String, Integer> unsupportedByExtension = new LinkedHashMap<>();
        int[] symbolicLinks = {0};
        int[] depthTruncations = {0};

        for (Path root : roots) {
            checkInterrupted();
            if (tracker.exhausted()) break;
            Files.walkFileTree(root, Set.of(), budget.maxDepth(), new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    if (name == null) return FileVisitResult.CONTINUE;
                    String directory = name.toString();
                    if (IGNORED_DIRECTORIES.contains(directory.toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (sensitiveFiles.deniesDirectory(directory)) {
                        warnings.add(new IndexBuildWarning(dir, SensitiveFilePolicy.REASON_ID,
                                SensitiveFilePolicy.message(), true));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink()) {
                        symbolicLinks[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs.isDirectory()) {
                        // walkFileTree reports a directory as an entry once maxDepth is reached.
                        depthTruncations[0]++;
                        return FileVisitResult.CONTINUE;
                    }
                    if (!attrs.isRegularFile()) return FileVisitResult.CONTINUE;

                    Path name = file.getFileName();
                    String fileName = name == null ? "" : name.toString();
                    if (sensitiveFiles.deniesFile(fileName)) {
                        warnings.add(new IndexBuildWarning(file, SensitiveFilePolicy.REASON_ID,
                                SensitiveFilePolicy.message(), true));
                        return FileVisitResult.CONTINUE;
                    }

                    var descriptor = readers.descriptor(file);
                    if (descriptor.isEmpty()) {
                        unsupportedByExtension.merge(extensionOf(fileName), 1, Integer::sum);
                        return FileVisitResult.CONTINUE;
                    }
                    DocumentReaderRegistry.ReaderDescriptor reader = descriptor.get();
                    if (attrs.size() > reader.maxFileSizeBytes()) {
                        warnings.add(new IndexBuildWarning(file, reader.id(),
                                "文件超过 reader 大小限制（" + reader.maxFileSizeBytes() + " bytes）", true));
                        return FileVisitResult.CONTINUE;
                    }
                    if (!tracker.accept(attrs.size())) {
                        return FileVisitResult.TERMINATE;
                    }
                    documents.add(new ScannedDocument(file, root, reader.id(), reader.version()));
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    warnings.add(new IndexBuildWarning(file, "filesystem",
                            "无法访问文件：" + failure.getMessage(), true));
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        if (!roots.isEmpty()) {
            Path reference = roots.get(0);
            if (tracker.exhausted()) {
                warnings.add(new IndexBuildWarning(reference, "scan-budget", tracker.reason(), true));
            }
            if (!unsupportedByExtension.isEmpty()) {
                warnings.add(new IndexBuildWarning(reference, "unsupported-format",
                        unsupportedMessage(unsupportedByExtension), false));
            }
            if (symbolicLinks[0] > 0) {
                warnings.add(new IndexBuildWarning(reference, "symbolic-link",
                        "跳过 " + symbolicLinks[0] + " 个符号链接或联接点（不跟随，避免遍历环路与越权读取）", false));
            }
            if (depthTruncations[0] > 0) {
                warnings.add(new IndexBuildWarning(reference, "scan-budget",
                        "有 " + depthTruncations[0] + " 个目录达到最大深度 " + budget.maxDepth()
                                + "，其内容未进入索引（可用 -D" + ScanBudget.MAX_DEPTH_PROPERTY + " 调整）", true));
            }
        }
        return new ScanResult(roots, List.copyOf(documents), List.copyOf(warnings));
    }

    /**
     * Drops roots nested inside another root so one file cannot be indexed twice under two different
     * relative paths, which would duplicate chunks and retrieval hits.
     */
    private static List<Path> outermostRoots(List<Path> sourceRoots) {
        LinkedHashSet<Path> normalized = sourceRoots.stream()
                .map(Path::toAbsolutePath).map(Path::normalize)
                .filter(Files::isDirectory)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<Path> result = new ArrayList<>();
        for (Path candidate : normalized) {
            boolean nested = normalized.stream()
                    .anyMatch(other -> !other.equals(candidate) && candidate.startsWith(other));
            if (!nested) result.add(candidate);
        }
        return List.copyOf(result);
    }

    private static String unsupportedMessage(Map<String, Integer> unsupportedByExtension) {
        int total = unsupportedByExtension.values().stream().mapToInt(Integer::intValue).sum();
        String detail = unsupportedByExtension.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_REPORTED_EXTENSIONS)
                .map(entry -> entry.getKey() + " " + entry.getValue())
                .collect(Collectors.joining("、"));
        String suffix = unsupportedByExtension.size() > MAX_REPORTED_EXTENSIONS ? " 等" : "";
        return "跳过 " + total + " 个不支持格式的文件（" + detail + suffix + "）";
    }

    private static String extensionOf(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1) : NO_EXTENSION;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("索引构建已取消");
    }

    public record ScannedDocument(Path path, Path root, String readerId, int readerVersion) { }
    public record ScanResult(List<Path> roots, List<ScannedDocument> documents,
                             List<IndexBuildWarning> warnings) { }
}
