package com.simplerag.common.text;

import java.util.Locale;

/** Null-safe text normalization for identifiers, settings and external values. */
public final class TextValues {
    private TextValues() {
    }

    public static String trimToEmpty(String value) {
        return value == null ? "" : value.strip();
    }

    public static String normalizedKey(String value) {
        return trimToEmpty(value).toLowerCase(Locale.ROOT);
    }
}
