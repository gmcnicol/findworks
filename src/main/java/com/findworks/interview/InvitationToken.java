package com.findworks.interview;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
final class InvitationToken {

    private final InvitationProperties properties;

    InvitationToken(InvitationProperties properties) {
        this.properties = properties;
    }

    String activeKeyId() {
        return properties.requiredActiveKeyId();
    }

    String token(UUID invitationId, String keyId) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.secret(keyId).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            var value = mac.doFinal(("findworks-invitation-v1:" + invitationId).getBytes(StandardCharsets.UTF_8));
            return keyId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
