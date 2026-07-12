package com.simplerag.search.reader;

import com.simplerag.search.DocumentReadException;
import com.simplerag.search.DocumentReader;
import com.simplerag.search.DocumentSection;
import com.simplerag.search.DocumentTextUnit;
import com.simplerag.search.ReadDocument;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.SharedStrings;
import org.apache.poi.xssf.model.StylesTable;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class XlsxDocumentReader implements DocumentReader {
    public static final int VERSION = 1;
    private static final long MAX_FILE_SIZE = 32L * 1024 * 1024;
    private static final int MAX_SHEETS = 256;
    private static final int MAX_ROWS = 100_000;
    private static final int MAX_CELLS = 1_000_000;
    private static final int MAX_EXTRACTED_CHARACTERS = 8 * 1024 * 1024;

    @Override public String id() { return "xlsx-streaming"; }
    @Override public int version() { return VERSION; }
    @Override public Set<String> extensions() { return Set.of("xlsx"); }
    @Override public long maxFileSizeBytes() { return MAX_FILE_SIZE; }

    @Override
    public ReadDocument read(Path file, Path root) throws IOException {
        try (OPCPackage archive = OoxmlSecurity.open(file, "XLSX")) {
            XSSFReader workbook = new XSSFReader(archive);
            StylesTable styles = workbook.getStylesTable();
            SharedStrings strings = workbook.getSharedStringsTable();
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            XSSFReader.SheetIterator sheets = (XSSFReader.SheetIterator) workbook.getSheetsData();
            List<DocumentSection> sections = new ArrayList<>();
            Limits totals = new Limits();
            while (sheets.hasNext()) {
                if (sections.size() >= MAX_SHEETS) {
                    throw new DocumentReadException("XLSX 工作表数量超过安全限制（" + MAX_SHEETS + " 个）");
                }
                try (InputStream sheet = sheets.next()) {
                    String name = sheets.getSheetName();
                    SheetCollector collector = new SheetCollector(totals);
                    XMLReader parser = XMLHelper.newXMLReader();
                    parser.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings,
                            collector, formatter, false));
                    parser.parse(new InputSource(sheet));
                    if (!collector.units.isEmpty()) {
                        sections.add(new DocumentSection("sheet-" + (sections.size() + 1), name,
                                "工作表：" + name, "行", collector.units));
                    }
                }
            }
            if (sections.isEmpty()) throw new DocumentReadException("XLSX 未提取到可索引单元格文本");
            return ReaderSupport.document(file, root, id(), version(), sections, List.of());
        } catch (DocumentReadException expected) {
            throw expected;
        } catch (LimitExceeded limit) {
            throw new DocumentReadException(limit.getMessage(), limit);
        } catch (Exception failure) {
            throw OoxmlSecurity.failure("XLSX", failure);
        }
    }

    private static final class SheetCollector implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final Limits totals;
        private final List<DocumentTextUnit> units = new ArrayList<>();
        private StringBuilder row;
        private int rowNumber;

        private SheetCollector(Limits totals) { this.totals = totals; }

        @Override public void startRow(int rowNum) {
            rowNumber = rowNum + 1;
            row = new StringBuilder();
            totals.rows++;
            totals.check();
        }

        @Override public void endRow(int rowNum) {
            String value = row.toString().strip();
            if (!value.isBlank()) units.add(new DocumentTextUnit(rowNumber, value));
        }

        @Override public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            String value = ReaderSupport.normalize(formattedValue);
            if (value.isBlank()) return;
            if (!row.isEmpty()) row.append(" | ");
            row.append(value);
            totals.cells++;
            totals.characters += value.length();
            totals.check();
        }

        @Override public void headerFooter(String text, boolean isHeader, String tagName) { }
    }

    private static final class Limits {
        private int rows;
        private int cells;
        private int characters;
        private void check() {
            if (rows > MAX_ROWS || cells > MAX_CELLS || characters > MAX_EXTRACTED_CHARACTERS) {
                throw new LimitExceeded("XLSX 展开内容超过安全限制");
            }
        }
    }

    private static final class LimitExceeded extends RuntimeException {
        private LimitExceeded(String message) { super(message); }
    }
}
