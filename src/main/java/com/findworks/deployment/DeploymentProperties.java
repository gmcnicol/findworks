package com.findworks.deployment;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("findworks.deployment")
public record DeploymentProperties(
        String environment,
        String databaseId,
        String providerAccountId) {

    private static final Set<String> ENVIRONMENTS = Set.of("local", "staging", "production");

    public void validate(String role, String databaseUrl, String publicOrigin,
            boolean flywayEnabled, boolean runtimeEnabled, String runtimeImage) {
        if (!Set.of("local", "web", "worker", "migrate", "support", "recovery", "acceptance").contains(role)) {
            throw new IllegalStateException("findworks.process-role must be local, web, worker, migrate, support, recovery, or acceptance.");
        }
        if (!ENVIRONMENTS.contains(environment)) {
            throw new IllegalStateException("Deployment environment must be local, staging, or production.");
        }
        if ("local".equals(environment)) {
            return;
        }
        required(databaseId, "Deployment database identifier is required.");
        required(providerAccountId, "Deployment provider account identifier is required.");
        if ("local".equals(role)) {
            throw new IllegalStateException("A non-local deployment requires an explicit process role.");
        }
        if (("web".equals(role) || "worker".equals(role) || "support".equals(role)
                || "recovery".equals(role) || "acceptance".equals(role)) && flywayEnabled) {
            throw new IllegalStateException("Web, worker, support, recovery, and acceptance processes must start with Flyway disabled.");
        }
        if ("migrate".equals(role) && !flywayEnabled) {
            throw new IllegalStateException("The migrate process must run Flyway.");
        }
        if (!databaseUrl.startsWith("jdbc:postgresql:") || !databaseUrl.contains("sslmode=verify-full")) {
            throw new IllegalStateException("Non-local PostgreSQL must use verified TLS.");
        }
        var origin = URI.create(required(publicOrigin, "Canonical public origin is required."));
        if (!"https".equals(origin.getScheme()) || origin.getHost() == null || origin.getUserInfo() != null) {
            throw new IllegalStateException("Canonical public origin must be HTTPS.");
        }
        if ("worker".equals(role)) {
            if (!runtimeEnabled || runtimeImage == null
                    || !runtimeImage.matches("^[^\\s]+@sha256:[a-f0-9]{64}$")) {
                throw new IllegalStateException("Worker requires the digest-pinned isolated Pi runtime.");
            }
        }
    }

    private static String required(String value, String message) {
        if (value == null || value.isBlank() || value.length() > 500
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException(message);
        }
        return value;
    }
}
