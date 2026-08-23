package com.findworks.discovery;

import com.findworks.security.PilotTenant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class DiscoveryRepository {

    private final JdbcClient jdbc;
    private final PilotTenant tenant;

    DiscoveryRepository(JdbcClient jdbc, PilotTenant tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Transactional
    UUID create(String email, DiscoveryDraft draft) {
        var investigator = tenant.investigator(email);
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO discoveries (id, organisation_id, owner_membership_id, title, objective)
                VALUES (?, ?, ?, ?, ?)
                """).params(id, investigator.organisationId(), investigator.membershipId(),
                        draft.title(), draft.objective()).update();
        tenant.audit(investigator, "discovery_created", "discovery", id);
        return id;
    }

    @Transactional(readOnly = true)
    List<DiscoverySummary> all(String email) {
        var investigator = tenant.investigator(email);
        return jdbc.sql("""
                SELECT id, title, objective, updated_at
                FROM discoveries
                WHERE owner_membership_id = ? AND status = 'active'
                ORDER BY updated_at DESC
                """).param(investigator.membershipId()).query((rs, row) -> new DiscoverySummary(
                        rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("objective"),
                        rs.getTimestamp("updated_at").toInstant())).list();
    }

    @Transactional(readOnly = true)
    Discovery get(String email, UUID id) {
        var investigator = tenant.investigator(email);
        return jdbc.sql("""
                SELECT id, title, objective, created_at
                FROM discoveries
                WHERE id = ? AND owner_membership_id = ? AND status = 'active'
                """).params(id, investigator.membershipId()).query((rs, row) -> new Discovery(
                        rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("objective"),
                        rs.getTimestamp("created_at").toInstant())).optional()
                .orElseThrow(() -> new AccessDeniedException("Discovery access denied."));
    }

    record DiscoverySummary(UUID id, String title, String objective, Instant updatedAt) {}
    record Discovery(UUID id, String title, String objective, Instant createdAt) {}
}
