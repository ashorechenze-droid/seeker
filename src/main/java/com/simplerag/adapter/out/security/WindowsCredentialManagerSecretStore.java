package com.simplerag.adapter.out.security;

import com.simplerag.application.diagnostics.DiagnosticSink;
import com.simplerag.application.port.out.SecretStore;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Stores the API key as a Generic Credential for the current Windows user. */
public final class WindowsCredentialManagerSecretStore implements SecretStore {
    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;
    private static final String MARKER = "wincred:v1:SimpleRAG/API";
    private static final String TARGET = "SimpleRAG/API";
    private final SecretStore fallback;
    private final DiagnosticSink diagnostics;

    public WindowsCredentialManagerSecretStore(SecretStore fallback, DiagnosticSink diagnostics) {
        this.fallback = fallback;
        this.diagnostics = diagnostics == null ? DiagnosticSink.noop() : diagnostics;
    }

    @Override
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            deleteCredential();
            return "";
        }
        if (!isWindows()) return fallback.encrypt(plainText);
        try {
            byte[] bytes = plainText.getBytes(StandardCharsets.UTF_16LE);
            Credential credential = new Credential();
            credential.Type = CRED_TYPE_GENERIC;
            credential.TargetName = new WString(TARGET);
            credential.UserName = new WString(System.getProperty("user.name", "SimpleRAG"));
            credential.Persist = CRED_PERSIST_LOCAL_MACHINE;
            credential.CredentialBlobSize = bytes.length;
            credential.CredentialBlob = new Memory(bytes.length);
            credential.CredentialBlob.write(0, bytes, 0, bytes.length);
            credential.write();
            if (!CredentialsApi.INSTANCE.CredWriteW(credential, 0)) {
                throw new IllegalStateException("CredWriteW failed with Windows error " + Native.getLastError());
            }
            diagnostics.record("credential stored", "security", "API credential stored in Windows Credential Manager");
            return MARKER;
        } catch (RuntimeException failure) {
            diagnostics.record("credential fallback", "security", failure.getClass().getSimpleName(),
                    Map.of("backend", "application-encrypted"));
            return fallback.encrypt(plainText);
        }
    }

    @Override
    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        if (!MARKER.equals(encoded) || !isWindows()) return fallback.decrypt(encoded);
        PointerByReference reference = new PointerByReference();
        if (!CredentialsApi.INSTANCE.CredReadW(new WString(TARGET), CRED_TYPE_GENERIC, 0, reference)) return "";
        Pointer pointer = reference.getValue();
        Credential credential = new Credential(pointer);
        try {
            credential.read();
            byte[] bytes = credential.CredentialBlob.getByteArray(0, credential.CredentialBlobSize);
            return new String(bytes, StandardCharsets.UTF_16LE);
        } finally {
            CredentialsApi.INSTANCE.CredFree(pointer);
        }
    }

    private void deleteCredential() {
        if (isWindows()) CredentialsApi.INSTANCE.CredDeleteW(new WString(TARGET), CRED_TYPE_GENERIC, 0);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows");
    }

    private interface CredentialsApi extends StdCallLibrary {
        CredentialsApi INSTANCE = Native.load("Advapi32", CredentialsApi.class);
        boolean CredWriteW(Credential credential, int flags);
        boolean CredReadW(WString targetName, int type, int flags, PointerByReference credential);
        boolean CredDeleteW(WString targetName, int type, int flags);
        void CredFree(Pointer credential);
    }

    @Structure.FieldOrder({"dwLowDateTime", "dwHighDateTime"})
    public static final class FileTime extends Structure {
        public int dwLowDateTime;
        public int dwHighDateTime;
    }

    @Structure.FieldOrder({"Flags", "Type", "TargetName", "Comment", "LastWritten",
            "CredentialBlobSize", "CredentialBlob", "Persist", "AttributeCount", "Attributes",
            "TargetAlias", "UserName"})
    public static final class Credential extends Structure {
        public int Flags;
        public int Type;
        public WString TargetName;
        public WString Comment;
        public FileTime LastWritten = new FileTime();
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public WString TargetAlias;
        public WString UserName;

        public Credential() { }
        public Credential(Pointer pointer) { super(pointer); read(); }
    }
}
