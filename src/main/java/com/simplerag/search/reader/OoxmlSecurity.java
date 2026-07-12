package com.simplerag.search.reader;

import com.simplerag.search.DocumentReadException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;

import java.nio.file.Path;

final class OoxmlSecurity {
    private static final long MAX_EXPANDED_ENTRY = 64L * 1024 * 1024;
    private static final long MAX_XML_TEXT = 16L * 1024 * 1024;

    static {
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(MAX_EXPANDED_ENTRY);
        ZipSecureFile.setMaxTextSize(MAX_XML_TEXT);
    }

    private OoxmlSecurity() { }

    static OPCPackage open(Path file, String format) throws DocumentReadException {
        try {
            return OPCPackage.open(file.toFile(), PackageAccess.READ);
        } catch (Exception failure) {
            throw failure(format, failure);
        }
    }

    static DocumentReadException failure(String format, Throwable failure) {
        String message = ReaderSupport.safeMessage(failure);
        if (message.toLowerCase(java.util.Locale.ROOT).contains("zip bomb")) {
            return new DocumentReadException(format + " 触发压缩包安全限制，未建立索引", failure);
        }
        return new DocumentReadException(format + " 损坏或无法解析：" + message, failure);
    }
}
