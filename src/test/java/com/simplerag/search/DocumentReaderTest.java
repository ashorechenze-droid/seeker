package com.simplerag.search;

import com.simplerag.application.port.out.TextEmbedder;
import com.simplerag.model.DocumentChunk;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentReaderTest {
    @TempDir Path temporaryDirectory;
    private final DocumentReaderRegistry readers = new DocumentReaderRegistry();
    private final ChunkerRegistry chunkers = new ChunkerRegistry();

    @Test
    void extractsPdfTextLayerWithPageLocationAndRejectsEncryptedPdf() throws Exception {
        Path pdf = temporaryDirectory.resolve("guide.pdf");
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(PDType1Font.HELVETICA, 12);
                content.newLineAtOffset(72, 720);
                content.showText("SimpleRAG PDF text layer supports searchable citations.");
                content.endText();
            }
            document.save(pdf.toFile());
        }

        ReadDocument extracted = readers.read(pdf, temporaryDirectory);
        List<DocumentChunk> chunks = chunkers.chunk(extracted);

        assertEquals("pdf-text-layer", extracted.readerId());
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.get(0).sourceLocation().startsWith("第 1 页"));

        Path encrypted = temporaryDirectory.resolve("secret.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            StandardProtectionPolicy protection = new StandardProtectionPolicy(
                    "owner-password", "user-password", new AccessPermission());
            protection.setEncryptionKeyLength(128);
            document.protect(protection);
            document.save(encrypted.toFile());
        }
        DocumentReadException failure = assertThrows(DocumentReadException.class,
                () -> readers.read(encrypted, temporaryDirectory));
        assertTrue(failure.getMessage().contains("加密") || failure.getMessage().contains("密码"));
    }

    @Test
    void extractsDocxPptxAndXlsxWithLogicalLocations() throws Exception {
        Path docx = temporaryDirectory.resolve("manual.docx");
        try (XWPFDocument document = new XWPFDocument(); OutputStream output = Files.newOutputStream(docx)) {
            var heading = document.createParagraph();
            heading.setStyle("Heading1");
            heading.createRun().setText("Installation");
            document.createParagraph().createRun().setText(
                    "Install the semantic model before rebuilding the local knowledge index.");
            document.write(output);
        }

        Path pptx = temporaryDirectory.resolve("slides.pptx");
        try (XMLSlideShow presentation = new XMLSlideShow(); OutputStream output = Files.newOutputStream(pptx)) {
            XSLFSlide slide = presentation.createSlide();
            slide.createTextBox().setText("Release checklist and rollback instructions");
            presentation.write(output);
        }

        Path xlsx = temporaryDirectory.resolve("settings.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(xlsx)) {
            var sheet = workbook.createSheet("Config");
            sheet.createRow(0).createCell(0).setCellValue("api.timeout.seconds");
            sheet.createRow(1).createCell(0).setCellValue("30");
            workbook.write(output);
        }

        List<DocumentChunk> docxChunks = chunkers.chunk(readers.read(docx, temporaryDirectory));
        List<DocumentChunk> pptxChunks = chunkers.chunk(readers.read(pptx, temporaryDirectory));
        List<DocumentChunk> xlsxChunks = chunkers.chunk(readers.read(xlsx, temporaryDirectory));

        assertTrue(docxChunks.stream().anyMatch(chunk -> chunk.sourceLocation().contains("章节：Installation")));
        assertTrue(pptxChunks.stream().anyMatch(chunk -> chunk.sourceLocation().contains("幻灯片 1")));
        assertTrue(xlsxChunks.stream().anyMatch(chunk -> chunk.sourceLocation().contains("工作表：Config")));
    }

    @Test
    void htmlReaderUsesMainContentAndPreservesHeadingLocation() throws Exception {
        Path html = temporaryDirectory.resolve("page.html");
        Files.writeString(html, """
                <html><head><title>Ignored shell title</title><script>secretScript()</script></head>
                <body><nav>navigation noise</nav><main><h2>Deployment</h2>
                <p>Restart the desktop client after replacing the local model files.</p></main></body></html>
                """);

        List<DocumentChunk> chunks = chunkers.chunk(readers.read(html, temporaryDirectory));

        assertTrue(chunks.stream().anyMatch(chunk -> chunk.sourceLocation().contains("章节：Deployment")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.content().contains("Restart the desktop client")));
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.content().contains("navigation noise")));
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.content().contains("secretScript")));
    }

    @Test
    void brokenNewFormatIsSkippedWithReasonWithoutAbortingSnapshot() throws Exception {
        Files.writeString(temporaryDirectory.resolve("good.txt"),
                "A healthy document remains searchable when another reader fails.");
        Files.writeString(temporaryDirectory.resolve("broken.pdf"), "not a valid PDF file");
        SemanticSearchEngine engine = new SemanticSearchEngine(new DisabledEmbedder());

        SemanticSearchEngine.IndexReport report = engine.index(List.of(temporaryDirectory), null);

        assertEquals(1, report.files());
        assertEquals(1, report.skipped());
        assertEquals(1, report.warnings().stream().filter(IndexBuildWarning::skipped).count());
        assertTrue(report.warnings().get(0).message().contains("PDF"));
        assertTrue(engine.snapshot().chunks().stream().anyMatch(chunk -> chunk.fileName().equals("good.txt")));
    }

    @Test
    void stripsByteOrderMarksAndReadsUtf16Documents() throws Exception {
        Path utf8WithBom = temporaryDirectory.resolve("signed.md");
        Files.write(utf8WithBom, concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF},
                "SimpleRAG 支持带签名的 UTF-8 文档。".getBytes(StandardCharsets.UTF_8)));

        Path utf16BigEndian = temporaryDirectory.resolve("wide-be.txt");
        Files.write(utf16BigEndian, "配置项 timeout 定义在部署文档中。".getBytes(StandardCharsets.UTF_16));

        Path utf16LittleEndian = temporaryDirectory.resolve("wide-le.txt");
        Files.write(utf16LittleEndian, concat(new byte[]{(byte) 0xFF, (byte) 0xFE},
                "回滚步骤记录在运维手册第三节。".getBytes(StandardCharsets.UTF_16LE)));

        List<DocumentChunk> signed = chunkers.chunk(readers.read(utf8WithBom, temporaryDirectory));
        List<DocumentChunk> bigEndian = chunkers.chunk(readers.read(utf16BigEndian, temporaryDirectory));
        List<DocumentChunk> littleEndian = chunkers.chunk(readers.read(utf16LittleEndian, temporaryDirectory));

        assertFalse(signed.isEmpty());
        assertFalse(signed.get(0).content().startsWith("﻿"), "BOM 不能残留进首个词法单元");
        assertTrue(signed.get(0).content().startsWith("SimpleRAG"));
        assertTrue(bigEndian.stream().anyMatch(chunk -> chunk.content().contains("定义在部署文档中")),
                "带 BOM 的 UTF-16BE 文档不应被当作二进制丢弃");
        assertTrue(littleEndian.stream().anyMatch(chunk -> chunk.content().contains("运维手册第三节")),
                "带 BOM 的 UTF-16LE 文档不应被当作二进制丢弃");
    }

    private static byte[] concat(byte[] prefix, byte[] body) {
        byte[] result = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        System.arraycopy(body, 0, result, prefix.length, body.length);
        return result;
    }

    private static final class DisabledEmbedder implements TextEmbedder {
        @Override public boolean isConfigured() { return false; }
        @Override public List<float[]> embed(List<String> texts) { return List.of(); }
        @Override public String modelName() { return "disabled"; }
        @Override public String status() { return "disabled"; }
        @Override public void close() { }
    }
}
