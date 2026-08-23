package com.findworks;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("findworks.pilot")
public record PilotProperties(UUID organisationId, String investigatorEmail, String investigatorPassword) {

    public PilotProperties {
        if (organisationId == null || investigatorEmail == null || !investigatorEmail.contains("@")
                || investigatorPassword == null || investigatorPassword.isBlank()) {
            throw new IllegalArgumentException("Pilot organisation, Investigator email, and password are required.");
        }
    }
}
