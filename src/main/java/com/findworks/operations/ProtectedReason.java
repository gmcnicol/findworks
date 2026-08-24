package com.findworks.operations;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
final class ProtectedReason {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final OperationsProperties properties;

    ProtectedReason(OperationsProperties properties) {
        this.properties = properties;
    }

    Encrypted encrypt(String reason, String associatedData) {
        if (reason == null || reason.isBlank() || reason.length() > 2000 || reason.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Break-glass reason is invalid.");
        }
        var nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new Encrypted(keyId(), nonce, cipher.doFinal(reason.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Break-glass reason protection failed.");
        }
    }

    String decrypt(String keyId, byte[] nonce, byte[] ciphertext, String associatedData) {
        if (!keyId().equals(keyId)) throw new IllegalStateException("Break-glass key is unavailable.");
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("Break-glass reason could not be opened.");
        }
    }

    private SecretKeySpec key() {
        try {
            var bytes = Base64.getDecoder().decode(properties.breakGlassKey());
            if (bytes.length != 32) throw new IllegalStateException("Break-glass key must contain 32 bytes.");
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Break-glass key is invalid.");
        }
    }

    private String keyId() {
        var keyId = properties.breakGlassKeyId();
        if (keyId == null || keyId.isBlank() || keyId.length() > 100) {
            throw new IllegalStateException("Break-glass key ID is not configured.");
        }
        return keyId;
    }

    record Encrypted(String keyId, byte[] nonce, byte[] ciphertext) {}
}
