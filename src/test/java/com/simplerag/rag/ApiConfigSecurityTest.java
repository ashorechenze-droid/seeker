package com.simplerag.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApiConfigSecurityTest {
    @Test void exposesOnlyNormalizedTargetHostAndRejectsEmbeddedCredentials() {
        assertEquals("example.com:8443", new ApiConfig("https://example.com:8443/v1", "", "m").targetHost());
        assertThrows(IllegalArgumentException.class,
                () -> new ApiConfig("https://user:secret@example.com/v1", "", "m").validateForModels());
        assertThrows(IllegalArgumentException.class,
                () -> new ApiConfig("file:///tmp/api", "", "m").validateForModels());
    }
}
