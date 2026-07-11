package com.simplerag.application.port.out;

public interface SecretStore {
    String encrypt(String plainText);
    String decrypt(String encoded);
}
