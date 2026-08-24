package com.findworks.deployment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeploymentPropertiesTest {

    private static final String TLS_DATABASE =
            "jdbc:postgresql://database.example.invalid/findworks?sslmode=verify-full";
    private static final String DIGEST = "findworks/pi@sha256:" + "a".repeat(64);

    @Test
    void productionRolesFailClosed() {
        var deployment = new DeploymentProperties("production", "pilot-db", "pilot-providers");

        assertThatCode(() -> deployment.validate(
                "web", TLS_DATABASE, "https://findworks.example", false, false, ""))
                .doesNotThrowAnyException();
        assertThatCode(() -> deployment.validate(
                "worker", TLS_DATABASE, "https://findworks.example", false, true, DIGEST))
                .doesNotThrowAnyException();
        assertThatCode(() -> deployment.validate(
                "migrate", TLS_DATABASE, "https://findworks.example", true, false, ""))
                .doesNotThrowAnyException();
        assertThatCode(() -> deployment.validate(
                "recovery", TLS_DATABASE, "https://findworks.example", false, false, ""))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> deployment.validate(
                "local", TLS_DATABASE, "https://findworks.example", false, true, DIGEST))
                .hasMessageContaining("explicit process role");
        assertThatThrownBy(() -> deployment.validate(
                "worker", TLS_DATABASE, "https://findworks.example", true, true, DIGEST))
                .hasMessageContaining("Flyway disabled");
        assertThatThrownBy(() -> deployment.validate(
                "recovery", TLS_DATABASE, "https://findworks.example", true, false, ""))
                .hasMessageContaining("Flyway disabled");
        assertThatThrownBy(() -> deployment.validate(
                "worker", TLS_DATABASE, "https://findworks.example", false, false, "findworks/pi:latest"))
                .hasMessageContaining("digest-pinned");
        assertThatThrownBy(() -> deployment.validate(
                "web", "jdbc:postgresql://database/findworks", "http://findworks.example", false, false, ""))
                .hasMessageContaining("verified TLS");
    }
}
