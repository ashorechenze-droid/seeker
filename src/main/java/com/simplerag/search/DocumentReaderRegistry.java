package com.simplerag.search;

import com.simplerag.common.text.TextValues;
import com.simplerag.search.reader.DocxDocumentReader;
import com.simplerag.search.reader.HtmlDocumentReader;
import com.simplerag.search.reader.PdfDocumentReader;
import com.simplerag.search.reader.PlainTextDocumentReader;
import com.simplerag.search.reader.PptxDocumentReader;
import com.simplerag.search.reader.XlsxDocumentReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Registry selecting a versioned reader without leaking format branches into scanning or ranking. */
public final class DocumentReaderRegistry {
    private final Map<String, DocumentReader> byExtension = new LinkedHashMap<>();
    private final Map<String, DocumentReader> byFileName = new LinkedHashMap<>();

    public DocumentReaderRegistry() {
        this(List.of(new PlainTextDocumentReader(), new PdfDocumentReader(), new DocxDocumentReader(),
                new PptxDocumentReader(), new XlsxDocumentReader(), new HtmlDocumentReader()));
    }

    public DocumentReaderRegistry(List<DocumentReader> readers) {
        for (DocumentReader reader : readers) register(reader);
    }

    private void register(DocumentReader reader) {
        for (String extension : reader.extensions()) {
            putUnique(byExtension, TextValues.normalizedKey(extension), reader);
        }
        for (String fileName : reader.fileNames()) {
            putUnique(byFileName, TextValues.normalizedKey(fileName), reader);
        }
    }

    public boolean canRead(Path path, long size) {
        return find(path).filter(reader -> size >= 0 && size <= reader.maxFileSizeBytes()).isPresent();
    }

    public Optional<ReaderDescriptor> descriptor(Path path) {
        return find(path).map(reader -> new ReaderDescriptor(reader.id(), reader.version(),
                reader.maxFileSizeBytes()));
    }

    public ReadDocument read(Path file, Path root) throws IOException {
        DocumentReader reader = find(file)
                .orElseThrow(() -> new DocumentReadException("不支持的文件格式"));
        long size = Files.size(file);
        if (size > reader.maxFileSizeBytes()) {
            throw new DocumentReadException("文件超过 " + reader.id() + " reader 的大小限制");
        }
        return reader.read(file, root);
    }

    private Optional<DocumentReader> find(Path path) {
        String name = TextValues.normalizedKey(path.getFileName().toString());
        DocumentReader exact = byFileName.get(name);
        if (exact != null) return Optional.of(exact);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return Optional.empty();
        return Optional.ofNullable(byExtension.get(name.substring(dot + 1)));
    }

    private static void putUnique(Map<String, DocumentReader> target, String key, DocumentReader reader) {
        DocumentReader previous = target.putIfAbsent(key, reader);
        if (previous != null) {
            throw new IllegalArgumentException("reader registration conflict for " + key + ": "
                    + previous.id() + " / " + reader.id());
        }
    }

    public record ReaderDescriptor(String id, int version, long maxFileSizeBytes) { }
}
