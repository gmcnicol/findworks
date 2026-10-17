package com.findworks.platform.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/** Accept only active Clerk session tokens, not arbitrary signed JWTs or pending sign-ups. */
public final class ClerkSessionValidator implements OAuth2TokenValidator<Jwt> {
    private final JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ofSeconds(5));

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (token.getExpiresAt() == null || token.getIssuedAt() == null
                || token.getIssuedAt().isAfter(Instant.now().plusSeconds(5))
                || !text(token, "sub") || !text(token, "sid")) {
            return invalid();
        }
        Object status = token.getClaims().get("sts");
        if (status != null && !"active".equals(status)) return invalid();
        return timestamps.validate(token);
    }

    private boolean text(Jwt token, String claim) {
        return token.getClaims().get(claim) instanceof String value && !value.isBlank();
    }

    private OAuth2TokenValidatorResult invalid() {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "An active Clerk session is required", null));
    }
}
