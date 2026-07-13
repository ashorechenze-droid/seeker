package com.simplerag.common.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextValuesTest {
    @Test
    void trimsNullAndWhitespaceSafely() {
        assertEquals("", TextValues.trimToEmpty(null));
        assertEquals("SimpleRAG", TextValues.trimToEmpty("  SimpleRAG  "));
    }

    @Test
    void normalizesConfigurationKeysWithoutUsingTheDefaultLocale() {
        assertEquals("api.example.com", TextValues.normalizedKey("  API.Example.COM "));
    }
}
