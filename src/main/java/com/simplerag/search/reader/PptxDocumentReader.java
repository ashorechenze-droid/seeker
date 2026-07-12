package com.simplerag.search.reader;

import com.simplerag.search.DocumentReadException;
import com.simplerag.search.DocumentReader;
import com.simplerag.search.DocumentSection;
import com.simplerag.search.DocumentTextUnit;
import com.simplerag.search.ReadDocument;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class PptxDocumentReader implements DocumentReader {
    public static final int VERSION = 1;
    private static final long MAX_FILE_SIZE = 32L * 1024 * 1024;
    private static final int MAX_SLIDES = 1_000;
    private static final int MAX_EXTRACTED_CHARACTERS = 8 * 1024 * 1024;

    @Override public String id() { return "pptx"; }
    @Override public int version() { return VERSION; }
    @Override public Set<String> extensions() { return Set.of("pptx"); }
    @Override public long maxFileSizeBytes() { return MAX_FILE_SIZE; }

    @Override
    public ReadDocument read(Path file, Path root) throws IOException {
        try (OPCPackage archive = OoxmlSecurity.open(file, "PPTX");
             XMLSlideShow presentation = new XMLSlideShow(archive)) {
            if (presentation.getSlides().size() > MAX_SLIDES) {
                throw new DocumentReadException("PPTX 幻灯片数量超过安全限制（" + MAX_SLIDES + " 页）");
            }
            List<DocumentSection> sections = new ArrayList<>();
            int characters = 0;
            for (int index = 0; index < presentation.getSlides().size(); index++) {
                XSLFSlide slide = presentation.getSlides().get(index);
                LinkedHashSet<String> texts = new LinkedHashSet<>();
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape) {
                        String text = ReaderSupport.normalize(textShape.getText());
                        if (!text.isBlank()) texts.add(text);
                    }
                }
                if (texts.isEmpty()) continue;
                List<DocumentTextUnit> units = new ArrayList<>();
                int line = 0;
                for (String text : texts) {
                    for (String part : text.split("\n")) {
                        if (!part.isBlank()) units.add(new DocumentTextUnit(++line, part.strip()));
                    }
                    characters += text.length();
                }
                if (characters > MAX_EXTRACTED_CHARACTERS) {
                    throw new DocumentReadException("PPTX 展开文本超过安全限制");
                }
                String title = ReaderSupport.normalize(slide.getTitle());
                String location = "幻灯片 " + (index + 1) + (title.isBlank() ? "" : "：" + title);
                sections.add(new DocumentSection("slide-" + (index + 1), title, location, "行", units));
            }
            if (sections.isEmpty()) throw new DocumentReadException("PPTX 未提取到可索引文本");
            return ReaderSupport.document(file, root, id(), version(), sections, List.of());
        } catch (DocumentReadException expected) {
            throw expected;
        } catch (Exception failure) {
            throw OoxmlSecurity.failure("PPTX", failure);
        }
    }
}
