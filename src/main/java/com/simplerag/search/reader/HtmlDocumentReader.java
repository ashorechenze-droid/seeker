package com.simplerag.search.reader;

import com.simplerag.search.DocumentReadException;
import com.simplerag.search.DocumentReader;
import com.simplerag.search.DocumentSection;
import com.simplerag.search.DocumentTextUnit;
import com.simplerag.search.ReadDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class HtmlDocumentReader implements DocumentReader {
    public static final int VERSION = 1;
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final int MAX_BLOCKS = 20_000;
    private static final int MAX_EXTRACTED_CHARACTERS = 8 * 1024 * 1024;
    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "li", "pre", "blockquote", "dt", "dd", "th", "td");

    @Override public String id() { return "html-main-content"; }
    @Override public int version() { return VERSION; }
    @Override public Set<String> extensions() { return Set.of("html", "htm"); }
    @Override public long maxFileSizeBytes() { return MAX_FILE_SIZE; }

    @Override
    public ReadDocument read(Path file, Path root) throws IOException {
        try {
            Document html = Jsoup.parse(file.toFile(), null);
            html.select("script,style,noscript,svg,canvas,nav,footer,aside,form").remove();
            Element content = html.selectFirst("main,article,[role=main]");
            if (content == null) content = html.body();
            if (content == null) throw new DocumentReadException("HTML 不包含可读取的正文节点");
            List<DocumentSection> sections = new ArrayList<>();
            List<DocumentTextUnit> current = new ArrayList<>();
            String currentTitle = html.title().isBlank() ? "正文" : html.title().strip();
            int unitNumber = 0;
            int characters = 0;
            for (Element element : content.select("h1,h2,h3,h4,h5,h6,p,li,pre,blockquote,dt,dd,th,td")) {
                if (isNestedTextBlock(element, content)) continue;
                String tag = element.normalName().toLowerCase(Locale.ROOT);
                String text = ReaderSupport.normalize(tag.equals("pre") ? element.wholeText() : element.text());
                if (text.isBlank()) continue;
                if (tag.matches("h[1-6]")) {
                    addSection(sections, currentTitle, current);
                    current = new ArrayList<>();
                    currentTitle = text;
                    current.add(new DocumentTextUnit(++unitNumber, text));
                    characters += text.length();
                    if (unitNumber > MAX_BLOCKS || characters > MAX_EXTRACTED_CHARACTERS) {
                        throw new DocumentReadException("HTML 正文超过安全限制");
                    }
                    continue;
                }
                current.add(new DocumentTextUnit(++unitNumber, text));
                characters += text.length();
                if (unitNumber > MAX_BLOCKS || characters > MAX_EXTRACTED_CHARACTERS) {
                    throw new DocumentReadException("HTML 正文超过安全限制");
                }
            }
            addSection(sections, currentTitle, current);
            if (sections.isEmpty()) throw new DocumentReadException("HTML 未提取到可索引正文");
            return ReaderSupport.document(file, root, id(), version(), sections, List.of());
        } catch (DocumentReadException expected) {
            throw expected;
        } catch (IOException failure) {
            throw new DocumentReadException("HTML 损坏或无法解析：" + ReaderSupport.safeMessage(failure), failure);
        }
    }

    private static boolean isNestedTextBlock(Element element, Element content) {
        Element parent = element.parent();
        while (parent != null && parent != content) {
            if (BLOCK_TAGS.contains(parent.normalName())) return true;
            parent = parent.parent();
        }
        return false;
    }

    private static void addSection(List<DocumentSection> sections, String title,
                                   List<DocumentTextUnit> units) {
        if (units.isEmpty()) return;
        sections.add(new DocumentSection("section-" + (sections.size() + 1), title,
                "章节：" + title, "段落", List.copyOf(units)));
    }
}
