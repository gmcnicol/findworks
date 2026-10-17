package com.findworks.platform.security;

import java.util.List;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public final class ClerkAuthorizedPartyValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_AZP = new OAuth2Error(
            "invalid_token", "The Clerk token authorized party is not allowed", null);

    private final List<String> authorizedParties;

    public ClerkAuthorizedPartyValidator(List<String> authorizedParties) {
        if (authorizedParties == null || authorizedParties.isEmpty()) {
            throw new IllegalArgumentException("At least one Clerk authorized party is required");
        }
        this.authorizedParties = List.copyOf(authorizedParties);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        Object authorizedParty = token.getClaims().get("azp");
        return authorizedParty instanceof String && authorizedParties.contains(authorizedParty)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(INVALID_AZP);
    }
}
