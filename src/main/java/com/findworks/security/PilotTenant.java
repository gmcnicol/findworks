package com.findworks.security;

import com.findworks.PilotProperties;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public final class PilotTenant {

    private final JdbcClient jdbc;
    private final PilotProperties properties;

    public PilotTenant(JdbcClient jdbc, PilotProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    public void select() {
        jdbc.sql("SET LOCAL ROLE findworks_application").update();
        jdbc.sql("SELECT set_config('findworks.organisation_id', ?, true)")
                .param(properties.organisationId().toString()).query(String.class).single();
    }

    public Investigator investigator(String email) {
        select();
        return jdbc.sql("""
                SELECT m.id membership_id, u.id user_id
                FROM memberships m
                JOIN users u ON u.id = m.user_id
                WHERE m.organisation_id = ? AND m.role = 'investigator'
                  AND u.email_verified_at IS NOT NULL AND lower(u.email) = lower(?)
                """).params(properties.organisationId(), email)
                .query((rs, row) -> new Investigator(
                        rs.getObject("membership_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        properties.organisationId()))
                .optional()
                .orElseThrow(() -> new AccessDeniedException("Investigator access required."));
    }

    public void audit(Investigator investigator, String action, String resourceKind, UUID resourceId) {
        jdbc.sql("""
                INSERT INTO audit_records
                    (id, organisation_id, actor_kind, actor_id, action, resource_kind, resource_id, outcome, correlation_id)
                VALUES (?, ?, 'investigator', ?, ?, ?, ?, 'success', ?)
                """).params(UUID.randomUUID(), investigator.organisationId(), investigator.membershipId(),
                        action, resourceKind, resourceId, UUID.randomUUID()).update();
    }

    public record Investigator(UUID membershipId, UUID userId, UUID organisationId) {}
}
