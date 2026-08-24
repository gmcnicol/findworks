package com.findworks.security;

import com.findworks.PilotProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Component
public final class PilotTenant {

    private final JdbcClient jdbc;
    private final PilotProperties properties;
    private final Clock clock;
    private final String databaseRole;

    public PilotTenant(JdbcClient jdbc, PilotProperties properties, Clock clock,
            @Value("${findworks.process-role:local}") String processRole) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.databaseRole = "web".equals(processRole) ? "findworks_application" : "findworks_worker";
    }

    public void select() {
        jdbc.sql("SET LOCAL ROLE " + databaseRole).update();
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
        audit(investigator.organisationId(), "investigator", investigator.membershipId(), action, resourceKind, resourceId);
    }

    public void auditSystem(String action, String resourceKind, UUID resourceId) {
        audit(properties.organisationId(), "system", null, action, resourceKind, resourceId);
    }

    public void auditSystemDenied(String action, String resourceKind) {
        audit(properties.organisationId(), "system", null, action, resourceKind, null, "denied");
    }

    private void audit(UUID organisationId, String actorKind, UUID actorId,
            String action, String resourceKind, UUID resourceId) {
        audit(organisationId, actorKind, actorId, action, resourceKind, resourceId, "success");
    }

    private void audit(UUID organisationId, String actorKind, UUID actorId,
            String action, String resourceKind, UUID resourceId, String outcome) {
        jdbc.sql("""
                INSERT INTO audit_records
                    (id, organisation_id, actor_kind, actor_id, action, resource_kind, resource_id,
                     outcome, correlation_id, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz + interval '12 months')
                """).params(UUID.randomUUID(), organisationId, actorKind, actorId, action, resourceKind,
                        resourceId, outcome, UUID.randomUUID(), Timestamp.from(clock.instant()),
                        Timestamp.from(clock.instant())).update();
    }

    public record Investigator(UUID membershipId, UUID userId, UUID organisationId) {}
}
