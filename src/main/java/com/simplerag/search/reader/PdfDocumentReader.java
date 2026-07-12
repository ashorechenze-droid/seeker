package com.simplerag.search.reader;

import com.simplerag.search.DocumentReadException;
import com.simplerag.search.DocumentReader;
import com.simplerag.search.DocumentSection;
import com.simplerag.search.DocumentTextUnit;
import com.simplerag.search.ReadDocument;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class PdfDocumentReader implements DocumentReader {
    public static final int VERSION = 1;
    private static final long MAX_FILE_SIZE = 32L * 1024 * 1024;
    private static final int MAX_PAGES = 1_000;
    private static final int MAX_EXTRACTED_CHARACTERS = 8 * 1024 * 1024;

    @Override public String id() { return "pdf-text-layer"; }
    @Override public int version() { return VERSION; }
    @Override public Set<String> extensions() { return Set.of("pdf"); }
    @Override public long maxFileSizeBytes() { return MAX_FILE_SIZE; }

    @Override
    public ReadDocument read(Path file, Path root) throws IOException {
        try (PDDocument pdf = PDDocument.load(file.toFile(), MemoryUsageSetting.setupMixed(32L * 1024 * 1024))) {
            if (pdf.isEncrypted()) throw new DocumentReadException("PDF 已加密，未建立索引");
            int pages = pdf.getNumberOfPages();
            if (pages > MAX_PAGES) throw new DocumentReadException("PDF 页数超过安全限制（" + MAX_PAGES + " 页）");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<DocumentSection> sections = new ArrayList<>();
            int extractedCharacters = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                List<DocumentTextUnit> units = ReaderSupport.lineUnits(stripper.getText(pdf));
                if (units.isEmpty()) continue;
                extractedCharacters += units.stream().mapToInt(unit -> unit.text().length()).sum();
                if (extractedCharacters > MAX_EXTRACTED_CHARACTERS) {
                    throw new DocumentReadException("PDF 文本层超过安全限制（8 MiB）");
                }
                sections.add(new DocumentSection("page-" + page, "", "第 " + page + " 页", "L", units));
            }
            if (sections.isEmpty()) {
                throw new DocumentReadException("PDF 未包含可索引文本层；本阶段未启用 OCR");
            }
            return ReaderSupport.document(file, root, id(), version(), sections, List.of());
        } catch (InvalidPasswordException encrypted) {
            throw new DocumentReadException("PDF 已加密或需要密码，未建立索引", encrypted);
        } catch (DocumentReadException expected) {
            throw expected;
        } catch (IOException damaged) {
            throw new DocumentReadException("PDF 损坏或无法解析：" + ReaderSupport.safeMessage(damaged), damaged);
        }
    }
}
