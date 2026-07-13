package com.simplerag.adapter.out.security;

import com.simplerag.common.crypto.Digests;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class SecretCodec implements com.simplerag.application.port.out.SecretStore {
    private static final SecureRandom RANDOM = new SecureRandom();

    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return "";
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (Exception failure) {
            throw new IllegalStateException("无法加密 API Key", failure);
        }
    }

    public String decrypt(String encoded) {
        if (encoded == null || encoded.isBlank()) return "";
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            byte[] iv = Arrays.copyOfRange(payload, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            return "";
        }
    }

    private static SecretKeySpec key() throws Exception {
        String material = System.getProperty("user.name", "") + "|"
                + System.getProperty("user.home", "") + "|SimpleRAG-local-secret-v1";
        byte[] digest = Digests.sha256Utf8(material);
        return new SecretKeySpec(Arrays.copyOf(digest, 16), "AES");
    }
}
