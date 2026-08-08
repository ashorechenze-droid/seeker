package com.simplerag.application.dto;

import java.nio.file.Path;
import java.util.List;

/**
 * Extracted text for the in-app file viewer, produced by the same readers that feed the index, so
 * what the page shows is what retrieval actually sees.
 *
 * @param lineLabels one label per line of {@code text}, carrying the reader's own numbering
 *                   (line, page, slide or worksheet row); blank for section separators
 * @param truncated  whether the extraction stopped at the viewer limit
 * @param notice     why there is no body, or what was cut; empty when the full text is present
 */
public record FileContentView(Path path, String readerId, String text, List<String> lineLabels,
                              boolean truncated, String notice) {
    public FileContentView {
        readerId = readerId == null ? "" : readerId;
        text = text == null ? "" : text;
        lineLabels = lineLabels == null ? List.of() : List.copyOf(lineLabels);
        notice = notice == null ? "" : notice;
    }

    public static FileContentView unavailable(Path path, String notice) {
        return new FileContentView(path, "", "", List.of(), false, notice);
    }
}
