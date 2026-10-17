package com.findworks.platform.config;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "findworks.clerk")
public record ClerkProperties(
        boolean enabled,
        String publishableKey,
        String frontendApi,
        String issuer,
        String jwksUri,
        String secretKey,
        List<String> authorizedParties
) {
    public ClerkProperties {
        authorizedParties = authorizedParties == null ? List.of() : List.copyOf(authorizedParties);
    }

    public void validate() {
        for (String value : new String[] {publishableKey, secretKey, frontendApi, issuer, jwksUri}) {
            if (value == null || value.isBlank()) throw new IllegalStateException("Clerk configuration is incomplete");
        }
        if (!publishableKey.startsWith("pk_")) throw new IllegalStateException("A Clerk publishable key is required");
        requireSecureUrl(frontendApi);
        requireSecureUrl(issuer);
        requireSecureUrl(jwksUri);
        if (authorizedParties.isEmpty()) throw new IllegalStateException("Clerk authorized parties are required");
        for (String origin : authorizedParties) {
            requireSecureUrl(origin);
            URI uri = URI.create(origin);
            if (!uri.getPath().isEmpty() || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalStateException("Clerk authorized parties must be exact origins without a trailing slash");
            }
        }
    }

    private void requireSecureUrl(String value) {
        URI uri = URI.create(value);
        boolean loopback = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || (loopback && "http".equals(uri.getScheme())))) {
            throw new IllegalStateException("Clerk endpoints must use HTTPS (HTTP is allowed only on loopback for development)");
        }
    }
}
