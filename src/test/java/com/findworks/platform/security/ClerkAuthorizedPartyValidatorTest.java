package com.findworks.platform.security;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class ClerkAuthorizedPartyValidatorTest {

    private final ClerkAuthorizedPartyValidator validator =
            new ClerkAuthorizedPartyValidator(List.of("https://findworks.example"));

    @Test
    void accepts_configured_authorized_party() {
        assertThat(validator.validate(jwt("https://findworks.example")).hasErrors()).isFalse();
    }

    @Test
    void rejects_missing_or_untrusted_authorized_party() {
        assertThat(validator.validate(jwt("https://attacker.example")).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(null)).hasErrors()).isTrue();
    }

    private Jwt jwt(String authorizedParty) {
        var builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("user_123")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        if (authorizedParty != null) builder.claim("azp", authorizedParty);
        return builder.build();
    }
}
