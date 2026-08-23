package com.findworks.interview;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("findworks.invitation")
record InvitationProperties(
        String publicOrigin,
        String sender,
        String activeKeyId,
        String activeSecret,
        String previousKeyId,
        String previousSecret) {

    URI origin() {
        if (publicOrigin == null || publicOrigin.isBlank()) {
            throw new IllegalStateException("Canonical public HTTPS origin is not configured.");
        }
        var origin = URI.create(publicOrigin);
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null
                || origin.getQuery() != null || origin.getFragment() != null
                || !(origin.getPath().isEmpty() || "/".equals(origin.getPath()))) {
            throw new IllegalStateException("Canonical public origin must be an HTTPS origin.");
        }
        return origin;
    }

    String requiredSender() {
        if (sender == null || sender.length() > 320
                || !sender.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalStateException("Invitation sender identity is not configured.");
        }
        return sender;
    }

    String requiredActiveKeyId() {
        if (activeKeyId == null || activeKeyId.isBlank() || activeKeyId.length() > 100) {
            throw new IllegalStateException("Invitation token key ID is not configured.");
        }
        secret(activeKeyId);
        return activeKeyId;
    }

    String secret(String keyId) {
        var secret = keyId != null && keyId.equals(activeKeyId) ? activeSecret
                : keyId != null && keyId.equals(previousKeyId) ? previousSecret : null;
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("Invitation token secret is unavailable or too short.");
        }
        return secret;
    }
}
