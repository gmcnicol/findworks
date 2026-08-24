package com.findworks.recovery;

import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("findworks.recovery")
public record RecoveryProperties(boolean requireVerifiedGate, boolean backupRequired,
        Duration maximumDrillAge, UUID operatorId, String bundleKeyId, String bundleKey,
        String applicationImageDigest) {

    public RecoveryProperties {
        maximumDrillAge = maximumDrillAge == null ? Duration.ofDays(90) : maximumDrillAge;
        if (maximumDrillAge.isNegative() || maximumDrillAge.isZero()) {
            throw new IllegalArgumentException("Recovery drill age is invalid.");
        }
    }

    UUID requiredOperatorId() {
        if (operatorId == null) throw new IllegalStateException("Recovery operator identity is not configured.");
        return operatorId;
    }

    SecretKeySpec signingKey() {
        try {
            var bytes = Base64.getDecoder().decode(bundleKey);
            if (bytes.length != 32) throw new IllegalStateException("Recovery bundle key must contain 32 bytes.");
            return new SecretKeySpec(bytes, "HmacSHA256");
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Recovery bundle key is invalid.");
        }
    }

    String requiredKeyId() {
        if (bundleKeyId == null || bundleKeyId.isBlank() || bundleKeyId.length() > 100) {
            throw new IllegalStateException("Recovery bundle key ID is not configured.");
        }
        return bundleKeyId;
    }

    String requiredImageDigest() {
        if (applicationImageDigest == null
                || !applicationImageDigest.matches("^.+@sha256:[a-f0-9]{64}$")) {
            throw new IllegalStateException("Recovery application image digest is invalid.");
        }
        return applicationImageDigest;
    }
}
