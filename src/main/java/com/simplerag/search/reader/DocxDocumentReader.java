package com.simplerag.search.reader;

import com.simplerag.search.DocumentReadException;
import com.simplerag.search.DocumentReader;
import com.simplerag.search.DocumentSection;
import com.simplerag.search.DocumentTextUnit;
import com.simplerag.search.ReadDocument;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DocxDocumentReader implements DocumentReader {
    public static final int VERSION = 1;
    private static final long MAX_FILE_SIZE = 32L * 1024 * 1024;
    private static final int MAX_TEXT_UNITS = 20_000;
    private static final int MAX_EXTRACTED_CHARACTERS = 8 * 1024 * 1024;

    @Override public String id() { return "docx"; }
    @Override public int version() { return VERSION; }
    @Override public Set<String> extensions() { return Set.of("docx"); }
    @Override public long maxFileSizeBytes() { return MAX_FILE_SIZE; }

    @Override
    public ReadDocument read(Path file, Path root) throws IOException {
        try (OPCPackage archive = OoxmlSecurity.open(file, "DOCX");
             XWPFDocument document = new XWPFDocument(archive)) {
            List<DocumentSection> sections = new ArrayList<>();
            List<DocumentTextUnit> current = new ArrayList<>();
            String currentTitle = "正文";
            int unitNumber = 0;
            int characters = 0;
            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    String text = ReaderSupport.normalize(paragraph.getText());
                    if (text.isBlank()) continue;
                    if (isHeading(paragraph)) {
                        addSection(sections, currentTitle, current);
                        current = new ArrayList<>();
                        currentTitle = text;
                        current.add(new DocumentTextUnit(++unitNumber, text));
                        characters += text.length();
                        requireWithinLimits(unitNumber, characters);
                        continue;
                    }
                    current.add(new DocumentTextUnit(++unitNumber, text));
                    characters += text.length();
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        String text = row.getTableCells().stream().map(XWPFTableCell::getText)
                                .map(ReaderSupport::normalize).filter(value -> !value.isBlank())
                                .reduce((left, right) -> left + " | " + right).orElse("");
                        if (!text.isBlank()) {
                            current.add(new DocumentTextUnit(++unitNumber, text));
                            characters += text.length();
                        }
                    }
                }
                requireWithinLimits(unitNumber, characters);
            }
            addSection(sections, currentTitle, current);
            if (sections.isEmpty()) throw new DocumentReadException("DOCX 未提取到可索引文本");
            return ReaderSupport.document(file, root, id(), version(), sections, List.of());
        } catch (DocumentReadException expected) {
            throw expected;
        } catch (Exception failure) {
            throw OoxmlSecurity.failure("DOCX", failure);
        }
    }

    private static boolean isHeading(XWPFParagraph paragraph) {
        String style = paragraph.getStyle();
        if (style == null) return false;
        String normalized = style.toLowerCase(Locale.ROOT);
        return normalized.startsWith("heading") || normalized.startsWith("标题");
    }

    private static void addSection(List<DocumentSection> sections, String title,
                                   List<DocumentTextUnit> units) {
        if (units.isEmpty()) return;
        sections.add(new DocumentSection("section-" + (sections.size() + 1), title,
                "章节：" + title, "段落", List.copyOf(units)));
    }

    private static void requireWithinLimits(int units, int characters) throws DocumentReadException {
        if (units > MAX_TEXT_UNITS || characters > MAX_EXTRACTED_CHARACTERS) {
            throw new DocumentReadException("DOCX 展开文本超过安全限制");
        }
    }
}
