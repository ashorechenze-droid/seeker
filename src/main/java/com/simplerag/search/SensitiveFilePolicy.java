package com.simplerag.search;

import com.simplerag.common.text.TextValues;

import java.util.Set;

/**
 * Credential-material exclusion policy applied before any document is read.
 *
 * <p>Retrieved chunk content can leave the machine through remote RAG, so key material must never
 * enter an index in the first place. This is a pure naming policy without filesystem access, which
 * keeps it independent from traversal, reading and ranking.
 */
public final class SensitiveFilePolicy {
    /** Escape hatch for knowledge bases that intentionally contain credential-shaped sample files. */
    public static final String INCLUDE_PROPERTY = "simplerag.index.includeSensitiveFiles";

    public static final String REASON_ID = "sensitive-file";

    private static final Set<String> FILE_NAMES = Set.of(
            ".env", ".netrc", "_netrc", ".npmrc", ".pypirc", ".htpasswd", ".git-credentials",
            "credentials", "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
            "secrets.yaml", "secrets.yml", "secrets.json");

    private static final Set<String> EXTENSIONS = Set.of(
            "pem", "key", "p12", "pfx", "jks", "keystore", "kdbx", "ppk", "asc", "gpg");

    private static final Set<String> DIRECTORIES = Set.of(
            ".ssh", ".gnupg", ".aws", ".azure", ".kube");

    private static final String NAME_PREFIX = ".env.";

    private final boolean enabled;

    public SensitiveFilePolicy() {
        this(!Boolean.getBoolean(INCLUDE_PROPERTY));
    }

    public SensitiveFilePolicy(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** True when a directory holds credential material and must not be traversed at all. */
    public boolean deniesDirectory(String directoryName) {
        if (!enabled) return false;
        return DIRECTORIES.contains(TextValues.normalizedKey(directoryName));
    }

    /** True when a file name looks like a private key, token store or credential file. */
    public boolean deniesFile(String fileName) {
        if (!enabled) return false;
        String name = TextValues.normalizedKey(fileName);
        if (name.isEmpty()) return false;
        if (FILE_NAMES.contains(name) || name.startsWith(NAME_PREFIX)) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1 && EXTENSIONS.contains(name.substring(dot + 1));
    }

    public static String message() {
        return "疑似凭据文件，已按安全策略跳过（设置 -D" + INCLUDE_PROPERTY + "=true 可关闭）";
    }
}
