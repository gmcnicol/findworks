package com.findworks.retention;

import com.findworks.PilotProperties;
import com.findworks.security.PilotTenant;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RetentionRepository {

    private final JdbcClient jdbc;
    private final PilotTenant tenant;
    private final PilotProperties pilot;
    private final Clock clock;

    RetentionRepository(JdbcClient jdbc, PilotTenant tenant, PilotProperties pilot, Clock clock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.pilot = pilot;
        this.clock = clock;
    }

    public Instant now() {
        return clock.instant();
    }

    public void scheduleReviewed(UUID discoveryId, Instant decidedAt) {
        var updated = jdbc.sql("""
                UPDATE discoveries d SET retention_due_at = greatest(
                    ?::timestamptz + o.retention_days * interval '1 day',
                    coalesce((SELECT max(e.extended_until) FROM retention_extensions e
                              WHERE e.discovery_id = d.id), '-infinity'::timestamptz))
                FROM organisations o
                WHERE d.id = ? AND d.organisation_id = o.id AND d.status = 'active'
                """).params(timestamp(decidedAt), discoveryId).update();
        if (updated != 1) {
            throw new IllegalArgumentException("Discovery retention scope changed.");
        }
    }

    @Transactional
    public void schedule() {
        tenant.select();
        var now = clock.instant();
        jdbc.sql("""
                UPDATE discoveries d
                SET retention_due_at = greatest(
                    d.last_activity_at + interval '180 days',
                    coalesce((SELECT max(e.extended_until) FROM retention_extensions e
                              WHERE e.discovery_id = d.id), d.last_activity_at + interval '180 days'))
                WHERE d.status = 'active'
                  AND NOT EXISTS (SELECT 1 FROM findings_package_decisions p
                      JOIN findings_package_versions v ON v.id = p.findings_package_version_id
                      WHERE v.discovery_id = d.id)
                """).update();
        jdbc.sql("""
                INSERT INTO retention_warnings (
                    id, organisation_id, discovery_id, due_at_snapshot, available_at
                ) SELECT gen_random_uuid(), organisation_id, id, retention_due_at, ?
                  FROM discoveries
                 WHERE status = 'active' AND retention_due_at > ?
                   AND retention_due_at <= ?::timestamptz + interval '14 days'
                ON CONFLICT (discovery_id, due_at_snapshot) DO NOTHING
                """).params(timestamp(now), timestamp(now), timestamp(now)).update();
        var due = jdbc.sql("""
                SELECT id FROM discoveries
                WHERE status = 'active' AND retention_due_at <= ?
                ORDER BY retention_due_at FOR UPDATE SKIP LOCKED
                """).param(timestamp(now)).query(UUID.class).list();
        due.forEach(id -> blockDiscovery(id, "system", null, now));
    }

    @Transactional
    public void extend(UUID discoveryId, Instant until, String operatorEmail) {
        var investigator = tenant.investigator(operatorEmail);
        var row = jdbc.sql("""
                SELECT retention_due_at FROM discoveries
                WHERE id = ? AND owner_membership_id = ? AND status = 'active' FOR UPDATE
                """).params(discoveryId, investigator.membershipId()).query((rs, ignored) ->
                        rs.getTimestamp("retention_due_at").toInstant()).optional()
                .orElseThrow(() -> new AccessDeniedException("Discovery retention access denied."));
        if (until == null || !until.isAfter(row)) {
            throw new IllegalArgumentException("Retention extension must be later than the current due date.");
        }
        jdbc.sql("""
                INSERT INTO retention_extensions (
                    id, organisation_id, discovery_id, previous_due_at, extended_until, actor_membership_id
                ) VALUES (?, ?, ?, ?, ?, ?)
                """).params(UUID.randomUUID(), investigator.organisationId(), discoveryId,
                timestamp(row), timestamp(until), investigator.membershipId()).update();
        jdbc.sql("UPDATE discoveries SET retention_due_at = ? WHERE id = ?")
                .params(timestamp(until), discoveryId).update();
        tenant.audit(investigator, "discovery_retention_extended", "discovery", discoveryId);
    }

    @Transactional
    public void requestDiscoveryDeletion(UUID discoveryId, String email) {
        var investigator = tenant.investigator(email);
        var owned = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM discoveries
                    WHERE id = ? AND owner_membership_id = ?)
                """).params(discoveryId, investigator.membershipId()).query(Boolean.class).single();
        if (!owned) {
            throw new AccessDeniedException("Discovery deletion access denied.");
        }
        blockDiscovery(discoveryId, "investigator", investigator.membershipId(), clock.instant());
        tenant.audit(investigator, "discovery_deletion_requested", "discovery", discoveryId);
    }

    @Transactional
    public void requestSessionDeletion(UUID missionId, UUID sessionId, String email) {
        var investigator = tenant.investigator(email);
        var now = clock.instant();
        var updated = jdbc.sql("""
                UPDATE interview_sessions s SET access_blocked_at = ?, purge_due_at = ?
                FROM discoveries d
                WHERE s.id = ? AND s.interview_mission_id = ?
                  AND d.id = s.discovery_id AND d.organisation_id = s.organisation_id
                  AND d.owner_membership_id = ? AND d.status = 'active'
                  AND s.access_blocked_at IS NULL
                """).params(timestamp(now), timestamp(now.plus(Duration.ofDays(7))), sessionId,
                missionId, investigator.membershipId()).update();
        var exists = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM interview_sessions s JOIN discoveries d ON d.id = s.discovery_id
                    WHERE s.id = ? AND s.interview_mission_id = ? AND d.owner_membership_id = ?)
                """).params(sessionId, missionId, investigator.membershipId()).query(Boolean.class).single();
        if (!exists) {
            throw new AccessDeniedException("Interview Session deletion access denied.");
        }
        if (updated == 1) {
            blockSession(sessionId, investigator.organisationId(), "investigator",
                    investigator.membershipId(), now);
            tenant.audit(investigator, "interview_session_deletion_requested", "interview_session", sessionId);
        }
    }

    @Transactional
    public Warning claimWarning() {
        tenant.select();
        var now = clock.instant();
        var leaseOwner = UUID.randomUUID();
        var executionCorrelation = UUID.randomUUID();
        return jdbc.sql("""
                UPDATE retention_warnings w SET status = 'leased', attempt_count = attempt_count + 1,
                    lease_owner = ?, lease_expires_at = now() + interval '5 minutes',
                    heartbeat_at = now(), execution_correlation_id = ?, updated_at = now()
                FROM discoveries d, memberships m, users u, organisations o
                WHERE w.id = (SELECT candidate.id FROM retention_warnings candidate
                    JOIN discoveries active ON active.id = candidate.discovery_id
                    WHERE candidate.available_at <= ? AND candidate.attempt_count < 3
                      AND active.status = 'active' AND active.retention_due_at = candidate.due_at_snapshot
                      AND (candidate.status = 'pending' OR
                           (candidate.status = 'leased' AND candidate.lease_expires_at <= now()))
                    ORDER BY candidate.available_at, candidate.created_at
                    FOR UPDATE OF candidate SKIP LOCKED LIMIT 1)
                  AND d.id = w.discovery_id AND m.id = d.owner_membership_id
                  AND u.id = m.user_id AND o.id = d.organisation_id
                RETURNING w.id, w.organisation_id, w.discovery_id, w.due_at_snapshot,
                          w.attempt_count, u.email, d.title, o.name,
                          w.origin_correlation_id, w.execution_correlation_id
                """).params(leaseOwner, executionCorrelation, timestamp(now)).query((rs, ignored) -> new Warning(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("discovery_id", UUID.class), rs.getTimestamp("due_at_snapshot").toInstant(),
                        rs.getInt("attempt_count"), rs.getString("email"), rs.getString("title"),
                        rs.getString("name"), leaseOwner,
                        rs.getObject("origin_correlation_id", UUID.class),
                        rs.getObject("execution_correlation_id", UUID.class))).optional().orElse(null);
    }

    @Transactional
    public boolean heartbeatWarning(Warning warning) {
        tenant.select();
        return jdbc.sql("""
                UPDATE retention_warnings SET heartbeat_at = now(),
                    lease_expires_at = now() + interval '5 minutes', updated_at = now()
                WHERE id = ? AND status = 'leased' AND lease_owner = ?
                  AND attempt_count = ? AND lease_expires_at >= now()
                """).params(warning.id(), warning.leaseOwner(), warning.attempt()).update() == 1;
    }

    @Transactional
    public void warningSent(Warning warning) {
        tenant.select();
        settleWarning(warning, "sent", null);
        tenant.auditSystem("retention_warning_sent", "discovery", warning.discoveryId());
    }

    @Transactional
    public void warningFailed(Warning warning, String errorClass) {
        tenant.select();
        var exhausted = warning.attempt() >= 3;
        settleWarning(warning, exhausted ? "failed" : "pending", errorClass);
    }

    @Transactional
    public Purge claimPurge() {
        scopeOwner();
        var now = clock.instant();
        var leaseOwner = UUID.randomUUID();
        var executionCorrelation = UUID.randomUUID();
        return jdbc.sql("""
                UPDATE deletion_ledger d SET stage = 'purging', attempts = attempts + 1,
                    lease_owner = ?, lease_expires_at = now() + interval '5 minutes',
                    heartbeat_at = now(), execution_correlation_id = ?, safe_error_class = NULL
                WHERE d.id = (SELECT id FROM deletion_ledger
                    WHERE available_at <= ? AND attempts < 20
                      AND (stage = 'blocked' OR (stage = 'purging' AND lease_expires_at <= now()))
                    ORDER BY available_at, requested_at FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING id, organisation_id, target_kind, target_id, attempts, lease_owner,
                          origin_correlation_id, execution_correlation_id
                """).params(leaseOwner, executionCorrelation, timestamp(now)).query((rs, ignored) -> new Purge(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getString("target_kind"), rs.getObject("target_id", UUID.class),
                        rs.getInt("attempts"), rs.getObject("lease_owner", UUID.class),
                        rs.getObject("origin_correlation_id", UUID.class),
                        rs.getObject("execution_correlation_id", UUID.class)))
                .optional().orElse(null);
    }

    @Transactional
    public boolean heartbeatPurge(Purge work) {
        scopeOwner();
        return jdbc.sql("""
                UPDATE deletion_ledger SET heartbeat_at = now(),
                    lease_expires_at = now() + interval '5 minutes'
                WHERE id = ? AND stage = 'purging' AND lease_owner = ?
                  AND attempts = ? AND lease_expires_at >= now()
                """).params(work.id(), work.leaseOwner(), work.attempt()).update() == 1;
    }

    @Transactional
    public void purge(Purge work) {
        scopeOwner();
        if ("discovery".equals(work.targetKind())) {
            jdbc.sql("DELETE FROM discoveries WHERE id = ? AND organisation_id = ? AND status = 'deletion_pending'")
                    .params(work.targetId(), work.organisationId()).update();
        } else {
            var participant = jdbc.sql("""
                    SELECT participant_id FROM interview_sessions
                    WHERE id = ? AND organisation_id = ? AND access_blocked_at IS NOT NULL
                    """).params(work.targetId(), work.organisationId()).query(UUID.class).optional();
            jdbc.sql("""
                    DELETE FROM invitations i USING interview_sessions s
                    WHERE s.id = ? AND s.organisation_id = ?
                      AND i.interview_mission_id = s.interview_mission_id
                      AND i.participant_id = s.participant_id
                    """).params(work.targetId(), work.organisationId()).update();
            jdbc.sql("DELETE FROM interview_sessions WHERE id = ? AND organisation_id = ? AND access_blocked_at IS NOT NULL")
                    .params(work.targetId(), work.organisationId()).update();
            participant.ifPresent(id -> jdbc.sql("""
                    DELETE FROM discovery_participants p WHERE p.id = ? AND p.organisation_id = ?
                      AND NOT EXISTS (SELECT 1 FROM invitations i WHERE i.participant_id = p.id)
                      AND NOT EXISTS (SELECT 1 FROM interview_sessions s WHERE s.participant_id = p.id)
                      AND NOT EXISTS (SELECT 1 FROM evidence e WHERE e.participant_id = p.id)
                    """).params(id, work.organisationId()).update());
        }
        var completed = clock.instant();
        jdbc.sql("""
                UPDATE deletion_ledger SET stage = 'completed', completed_at = ?,
                    backup_expiry_due_at = ?::timestamptz + interval '30 days', lease_owner = NULL,
                    lease_expires_at = NULL, heartbeat_at = NULL, safe_error_class = NULL
                WHERE id = ? AND stage = 'purging' AND lease_owner = ?
                """).params(timestamp(completed), timestamp(completed), work.id(), work.leaseOwner()).update();
    }

    @Transactional
    public void purgeFailed(Purge work) {
        scopeOwner();
        jdbc.sql("""
                UPDATE deletion_ledger SET stage = 'blocked', available_at = ?,
                    lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL,
                    safe_error_class = 'database_error'
                WHERE id = ? AND stage = 'purging' AND lease_owner = ?
                """).params(timestamp(clock.instant()), work.id(), work.leaseOwner()).update();
    }

    @Transactional
    public int pruneAudit() {
        tenant.select();
        return jdbc.sql("DELETE FROM audit_records WHERE expires_at <= ?")
                .param(timestamp(clock.instant())).update();
    }

    private void blockDiscovery(UUID discoveryId, String actorKind, UUID actorId, Instant now) {
        var organisationId = jdbc.sql("SELECT organisation_id FROM discoveries WHERE id = ? FOR UPDATE")
                .param(discoveryId).query(UUID.class).optional()
                .orElseThrow(() -> new AccessDeniedException("Discovery deletion access denied."));
        var deadline = now.plus(Duration.ofDays(7));
        var updated = jdbc.sql("""
                UPDATE discoveries SET status = 'deletion_pending', access_blocked_at = ?, purge_due_at = ?
                WHERE id = ? AND status = 'active'
                """).params(timestamp(now), timestamp(deadline), discoveryId).update();
        if (updated == 0) {
            return;
        }
        jdbc.sql("UPDATE invitations SET revoked_at = coalesce(revoked_at, ?), delivery_status = 'revoked' WHERE discovery_id = ?")
                .params(timestamp(now), discoveryId).update();
        jdbc.sql("""
                UPDATE interview_access_grants SET revoked_at = coalesce(revoked_at, ?)
                WHERE interview_session_id IN (SELECT id FROM interview_sessions WHERE discovery_id = ?)
                """).params(timestamp(now), discoveryId).update();
        jdbc.sql("UPDATE runtime_credentials SET revoked_at = coalesce(revoked_at, ?) WHERE discovery_id = ?")
                .params(timestamp(now), discoveryId).update();
        jdbc.sql("""
                UPDATE interview_runtime_runs SET status = 'cancelled', cancelled_at = ?,
                    lease_until = NULL, lease_owner = NULL, updated_at = ?
                WHERE discovery_id = ? AND status IN ('queued', 'running')
                """).params(timestamp(now), timestamp(now), discoveryId).update();
        jdbc.sql("UPDATE findings_package_versions SET invalidated_at = coalesce(invalidated_at, ?) WHERE discovery_id = ?")
                .params(timestamp(now), discoveryId).update();
        insertLedger(organisationId, "discovery", discoveryId, actorKind, actorId, now, deadline);
    }

    private void blockSession(UUID sessionId, UUID organisationId, String actorKind, UUID actorId, Instant now) {
        var deadline = now.plus(Duration.ofDays(7));
        jdbc.sql("""
                UPDATE invitations i SET revoked_at = coalesce(i.revoked_at, ?), delivery_status = 'revoked'
                FROM interview_sessions s WHERE s.id = ?
                  AND i.interview_mission_id = s.interview_mission_id
                  AND i.participant_id = s.participant_id
                """).params(timestamp(now), sessionId).update();
        jdbc.sql("UPDATE interview_access_grants SET revoked_at = coalesce(revoked_at, ?) WHERE interview_session_id = ?")
                .params(timestamp(now), sessionId).update();
        jdbc.sql("UPDATE runtime_credentials SET revoked_at = coalesce(revoked_at, ?) WHERE interview_session_id = ?")
                .params(timestamp(now), sessionId).update();
        jdbc.sql("""
                UPDATE interview_runtime_runs SET status = 'cancelled', cancelled_at = ?,
                    lease_until = NULL, lease_owner = NULL, updated_at = ?
                WHERE interview_session_id = ? AND status IN ('queued', 'running')
                """).params(timestamp(now), timestamp(now), sessionId).update();
        jdbc.sql("UPDATE findings_package_versions SET invalidated_at = coalesce(invalidated_at, ?) WHERE interview_session_id = ?")
                .params(timestamp(now), sessionId).update();
        insertLedger(organisationId, "interview_session", sessionId, actorKind, actorId, now, deadline);
    }

    private void insertLedger(UUID organisationId, String kind, UUID targetId,
            String actorKind, UUID actorId, Instant now, Instant deadline) {
        jdbc.sql("""
                INSERT INTO deletion_ledger (
                    id, organisation_id, target_kind, target_id, requested_by_kind,
                    requested_by_id, requested_at, access_blocked_at, purge_deadline, available_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (target_kind, target_id) DO NOTHING
                """).params(UUID.randomUUID(), organisationId, kind, targetId, actorKind, actorId,
                timestamp(now), timestamp(now), timestamp(deadline), timestamp(now)).update();
    }

    private void settleWarning(Warning warning, String status, String errorClass) {
        var sent = "sent".equals(status) ? timestamp(clock.instant()) : null;
        jdbc.sql("""
                UPDATE retention_warnings SET status = ?, sent_at = ?, error_class = ?,
                    available_at = ?, lease_owner = NULL, lease_expires_at = NULL,
                    heartbeat_at = NULL, updated_at = ?
                WHERE id = ? AND status = 'leased' AND lease_owner = ? AND attempt_count = ?
                """).params(status, sent, errorClass, timestamp(clock.instant()), timestamp(clock.instant()),
                warning.id(), warning.leaseOwner(), warning.attempt()).update();
    }

    private void scopeOwner() {
        jdbc.sql("SELECT set_config('findworks.organisation_id', ?, true)")
                .param(pilot.organisationId().toString()).query(String.class).single();
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    public record Warning(UUID id, UUID organisationId, UUID discoveryId, Instant dueAt,
            int attempt, String recipient, String discoveryTitle, String organisationName, UUID leaseOwner,
            UUID originCorrelationId, UUID executionCorrelationId) {}
    public record Purge(UUID id, UUID organisationId, String targetKind,
            UUID targetId, int attempt, UUID leaseOwner,
            UUID originCorrelationId, UUID executionCorrelationId) {}
}
