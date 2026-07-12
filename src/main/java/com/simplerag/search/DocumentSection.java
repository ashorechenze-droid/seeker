package com.simplerag.search;

import java.util.List;

/** A logical page or section with source-addressable text units. */
public record DocumentSection(String id, String title, String locationLabel,
                              String rangeLabel, List<DocumentTextUnit> units) {
    public DocumentSection {
        id = id == null ? "" : id;
        title = title == null ? "" : title.strip();
        locationLabel = locationLabel == null ? "" : locationLabel.strip();
        rangeLabel = rangeLabel == null || rangeLabel.isBlank() ? "L" : rangeLabel.strip();
        units = units == null ? List.of() : List.copyOf(units);
    }

    public String sourceLocation(int startInclusive, int endExclusive) {
        if (units.isEmpty() || startInclusive < 0 || endExclusive <= startInclusive
                || endExclusive > units.size()) return locationLabel;
        int start = units.get(startInclusive).number();
        int end = units.get(endExclusive - 1).number();
        String range = rangeLabel + start + (end == start ? "" : "-" + end);
        return locationLabel.isBlank() ? range : locationLabel + " · " + range;
    }
}
