package com.simplerag.search;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** Filesystem traversal policy; independent from reading, chunking and ranking. */
public final class DocumentScanner {
    private static final java.util.Set<String> IGNORED_DIRECTORIES = java.util.Set.of(
            ".git", ".idea", ".vscode", ".simplerag", "node_modules", "target", "build", "dist",
            "out", "vendor", ".venv", "venv", "__pycache__", ".next", ".gradle"
    );

    public ScanResult scan(List<Path> sourceRoots) throws IOException {
        return scan(sourceRoots, new DocumentReaderRegistry());
    }

    public ScanResult scan(List<Path> sourceRoots, DocumentReaderRegistry readers) throws IOException {
        LinkedHashSet<Path> roots = sourceRoots.stream()
                .map(Path::toAbsolutePath).map(Path::normalize)
                .filter(Files::isDirectory)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ScannedDocument> documents = new ArrayList<>();
        List<IndexBuildWarning> warnings = new ArrayList<>();
        for (Path root : roots) {
            checkInterrupted();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    return name != null && IGNORED_DIRECTORIES.contains(name.toString().toLowerCase(Locale.ROOT))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        readers.descriptor(file).ifPresent(reader -> {
                            if (attrs.size() <= reader.maxFileSizeBytes()) {
                                documents.add(new ScannedDocument(file, root, reader.id(), reader.version()));
                            } else {
                                warnings.add(new IndexBuildWarning(file, reader.id(),
                                        "文件超过 reader 大小限制（" + reader.maxFileSizeBytes() + " bytes）", true));
                            }
                        });
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFileFailed(Path file, IOException failure) {
                    warnings.add(new IndexBuildWarning(file, "filesystem",
                            "无法访问文件：" + failure.getMessage(), true));
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return new ScanResult(List.copyOf(roots), List.copyOf(documents), List.copyOf(warnings));
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("索引构建已取消");
    }

    public record ScannedDocument(Path path, Path root, String readerId, int readerVersion) { }
    public record ScanResult(List<Path> roots, List<ScannedDocument> documents,
                             List<IndexBuildWarning> warnings) { }
}
