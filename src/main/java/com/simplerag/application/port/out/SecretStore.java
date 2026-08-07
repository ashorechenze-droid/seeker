package com.simplerag.application.port.out;

public interface SecretStore {
    String encrypt(String plainText);
    String decrypt(String encoded);

    /** Namespaced variants allow independent credentials without breaking simple stores. */
    default String encrypt(String namespace, String plainText) { return encrypt(plainText); }
    default String decrypt(String namespace, String encoded) { return decrypt(encoded); }
}
