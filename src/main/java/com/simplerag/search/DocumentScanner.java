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
import java.util.Set;
import java.util.stream.Collectors;

/** Filesystem traversal policy; independent from reading, chunking and ranking. */
public final class DocumentScanner {
    private static final long MAX_FILE_SIZE = 2L * 1024 * 1024;
    private static final Set<String> IGNORED_DIRECTORIES = Set.of(
            ".git", ".idea", ".vscode", ".simplerag", "node_modules", "target", "build", "dist",
            "out", "vendor", ".venv", "venv", "__pycache__", ".next", ".gradle"
    );
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
            "md", "markdown", "txt", "rst", "adoc", "java", "kt", "kts", "py", "js", "jsx",
            "ts", "tsx", "go", "rs", "c", "h", "cpp", "hpp", "cs", "php", "rb", "swift",
            "scala", "sql", "sh", "bash", "ps1", "bat", "cmd", "html", "htm", "css", "scss",
            "less", "xml", "json", "jsonl", "yaml", "yml", "toml", "ini", "properties", "conf",
            "vue", "svelte", "gradle", "dockerfile"
    );

    public ScanResult scan(List<Path> sourceRoots) throws IOException {
        LinkedHashSet<Path> roots = sourceRoots.stream()
                .map(Path::toAbsolutePath).map(Path::normalize)
                .filter(Files::isDirectory)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<ScannedDocument> documents = new ArrayList<>();
        for (Path root : roots) {
            checkInterrupted();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    return name != null && IGNORED_DIRECTORIES.contains(name.toString().toLowerCase(Locale.ROOT))
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && attrs.size() <= MAX_FILE_SIZE && isSupported(file)) {
                        documents.add(new ScannedDocument(file, root));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return new ScanResult(List.copyOf(roots), List.copyOf(documents));
    }

    private static boolean isSupported(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.equals("dockerfile") || name.equals("makefile")
                || name.equals("readme") || name.equals("license")) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && SUPPORTED_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("索引构建已取消");
    }

    public record ScannedDocument(Path path, Path root) { }
    public record ScanResult(List<Path> roots, List<ScannedDocument> documents) { }
}
