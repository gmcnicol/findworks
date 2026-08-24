package com.findworks.runtime;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public final class RuntimeCheckpointCipher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final RuntimeProperties properties;

    public RuntimeCheckpointCipher(RuntimeProperties properties) {
        this.properties = properties;
    }

    public Encrypted encrypt(byte[] plaintext, String associatedData) {
        if (plaintext == null || plaintext.length == 0 || plaintext.length > 10_000_000) {
            throw new IllegalArgumentException("Runtime checkpoint is invalid.");
        }
        var nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(requiredKeyId(), nonce, cipher.doFinal(plaintext));
        } catch (Exception error) {
            throw new IllegalStateException("Runtime checkpoint encryption failed.", error);
        }
    }

    public byte[] decrypt(String keyId, byte[] nonce, byte[] ciphertext, String associatedData) {
        if (!requiredKeyId().equals(keyId)) {
            throw new IllegalStateException("Runtime checkpoint key is unavailable.");
        }
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(ciphertext);
        } catch (Exception error) {
            throw new IllegalStateException("Runtime checkpoint decryption failed.", error);
        }
    }

    private SecretKeySpec key() {
        try {
            var decoded = Base64.getDecoder().decode(properties.checkpointKey());
            if (decoded.length != 32) {
                throw new IllegalStateException("Runtime checkpoint key must contain 32 bytes.");
            }
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Runtime checkpoint key is invalid.", error);
        }
    }

    private String requiredKeyId() {
        var keyId = properties.checkpointKeyId();
        if (keyId == null || keyId.isBlank() || keyId.length() > 100) {
            throw new IllegalStateException("Runtime checkpoint key ID is not configured.");
        }
        return keyId;
    }

    public record Encrypted(String keyId, byte[] nonce, byte[] ciphertext) {
        public Encrypted {
            nonce = nonce.clone();
            ciphertext = ciphertext.clone();
        }

        @Override
        public byte[] nonce() {
            return nonce.clone();
        }

        @Override
        public byte[] ciphertext() {
            return ciphertext.clone();
        }
    }
}
