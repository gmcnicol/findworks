package com.findworks.operations;

import com.findworks.PilotProperties;
import com.findworks.operations.AlertReceiver.Event;
import com.findworks.operations.OperationalSignalSource.Signal;
import com.findworks.security.PilotTenant;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class OperationsRepository {

    private final JdbcClient jdbc;
    private final PilotTenant tenant;
    private final PilotProperties pilot;
    private final OperationsProperties properties;
    private final Clock clock;
    private final ProtectedReason reasons;
    private final BackupVerifier backups;

    OperationsRepository(JdbcClient jdbc, PilotTenant tenant, PilotProperties pilot,
            OperationsProperties properties, Clock clock, ProtectedReason reasons, BackupVerifier backups) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.pilot = pilot;
        this.properties = properties;
        this.clock = clock;
        this.reasons = reasons;
        this.backups = backups;
    }

    @Transactional(readOnly = true)
    public List<Signal> currentDatabaseSignals() {
        tenant.select();
        var cutoff = Timestamp.from(clock.instant().minus(properties.oldJobThreshold()));
        var signals = new ArrayList<Signal>();
        signals.addAll(jdbc.sql("""
                SELECT id FROM (
                    SELECT id, created_at observed_at FROM shaping_runtime_work WHERE status = 'queued'
                    UNION ALL SELECT id, available_at FROM interview_runtime_runs WHERE status = 'queued'
                    UNION ALL SELECT id, next_attempt_at FROM invitation_delivery_jobs WHERE status = 'pending'
                    UNION ALL SELECT id, available_at FROM retention_warnings WHERE status = 'pending'
                    UNION ALL SELECT id, available_at FROM deletion_ledger WHERE stage = 'blocked'
                ) jobs WHERE observed_at < ?
                """).param(cutoff).query(UUID.class).list().stream()
                .map(id -> new Signal(OperationalTelemetry.AlertKind.OLD_JOB,
                        OperationalTelemetry.SafeError.STALE, id)).toList());
        signals.addAll(jdbc.sql("SELECT id FROM interview_runtime_runs WHERE status = 'failed'")
                .query(UUID.class).list().stream().map(id -> new Signal(
                        OperationalTelemetry.AlertKind.RUNTIME_FAILURE,
                        OperationalTelemetry.SafeError.EXHAUSTED, id)).toList());
        signals.addAll(jdbc.sql("SELECT id FROM invitation_delivery_jobs WHERE status = 'failed'")
                .query(UUID.class).list().stream().map(id -> new Signal(
                        OperationalTelemetry.AlertKind.EMAIL_FAILURE,
                        OperationalTelemetry.SafeError.EXHAUSTED, id)).toList());
        signals.addAll(jdbc.sql("""
                SELECT id FROM deletion_ledger
                WHERE stage <> 'completed' AND purge_deadline < ?
                """).param(Timestamp.from(clock.instant())).query(UUID.class).list().stream()
                .map(id -> new Signal(OperationalTelemetry.AlertKind.PURGE_OVERDUE,
                        OperationalTelemetry.SafeError.OVERDUE, id)).toList());
        return signals;
    }

    @Transactional
    public List<Event> reconcile(List<Signal> observed) {
        tenant.select();
        var now = clock.instant();
        var unique = new LinkedHashMap<Key, Signal>();
        observed.forEach(signal -> unique.put(new Key(signal.kind(), signal.resourceId()), signal));
        var transitions = new ArrayList<Event>();
        for (var signal : unique.values()) {
            var existing = jdbc.sql("""
                    SELECT id FROM operational_alerts
                    WHERE alert_kind = ? AND status = 'firing'
                      AND affected_resource_id IS NOT DISTINCT FROM ?
                    """).params(signal.kind().value(), signal.resourceId()).query(UUID.class).optional();
            if (existing.isPresent()) {
                jdbc.sql("""
                        UPDATE operational_alerts SET last_observed_at = ?, safe_error_class = ? WHERE id = ?
                        """).params(Timestamp.from(now), error(signal), existing.get()).update();
            } else {
                var id = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO operational_alerts (
                            id, organisation_id, alert_kind, affected_resource_id, status,
                            safe_error_class, first_observed_at, last_observed_at
                        ) VALUES (?, ?, ?, ?, 'firing', ?, ?, ?)
                        """).params(id, pilot.organisationId(), signal.kind().value(), signal.resourceId(),
                        error(signal), Timestamp.from(now), Timestamp.from(now)).update();
                transitions.add(new Event(id, signal.kind(), true, signal.error(), signal.resourceId(), now));
            }
        }
        var firing = jdbc.sql("""
                SELECT id, alert_kind, affected_resource_id, safe_error_class
                FROM operational_alerts WHERE status = 'firing'
                """).query((rs, ignored) -> new CurrentAlert(
                        rs.getObject("id", UUID.class), alertKind(rs.getString("alert_kind")),
                        rs.getObject("affected_resource_id", UUID.class),
                        safeError(rs.getString("safe_error_class")))).list();
        for (var alert : firing) {
            if (!unique.containsKey(new Key(alert.kind(), alert.resourceId()))) {
                jdbc.sql("""
                        UPDATE operational_alerts SET status = 'resolved', resolved_at = ?, last_observed_at = ?
                        WHERE id = ? AND status = 'firing'
                        """).params(Timestamp.from(now), Timestamp.from(now), alert.id()).update();
                transitions.add(new Event(alert.id(), alert.kind(), false,
                        alert.error(), alert.resourceId(), now));
            }
        }
        return transitions;
    }

    @Transactional(readOnly = true)
    public Status status() {
        tenant.select();
        var firing = jdbc.sql("SELECT count(*) FROM operational_alerts WHERE status = 'firing'")
                .query(Integer.class).single();
        var queued = jdbc.sql("""
                SELECT sum(count) FROM (
                    SELECT count(*) count FROM shaping_runtime_work WHERE status = 'queued'
                    UNION ALL SELECT count(*) FROM interview_runtime_runs WHERE status = 'queued'
                    UNION ALL SELECT count(*) FROM invitation_delivery_jobs WHERE status = 'pending'
                    UNION ALL SELECT count(*) FROM retention_warnings WHERE status = 'pending'
                    UNION ALL SELECT count(*) FROM deletion_ledger WHERE stage = 'blocked'
                ) jobs
                """).query(Long.class).single();
        return new Status(firing, Math.toIntExact(queued));
    }

    @Transactional
    public Status operatorStatus(String reasonCode) {
        var operator = operator(reasonCode);
        var result = status();
        tenant.auditOperator(operator, "support_status_inspected", "operations", null);
        return result;
    }

    @Transactional
    public void retryWork(String kind, UUID id, String reasonCode) {
        var operator = operator(reasonCode);
        tenant.select();
        switch (kind) {
            case "invitation_email" -> retryInvitation(id);
            case "findings_extraction" -> retryFindings(id);
            default -> throw new IllegalArgumentException("Work kind cannot be safely retried.");
        }
        tenant.auditOperator(operator, "support_work_retried", kind, id);
    }

    @Transactional
    public void extendRetention(UUID discoveryId, Instant until, String reasonCode) {
        var operator = operator(reasonCode);
        tenant.select();
        var current = jdbc.sql("""
                SELECT retention_due_at FROM discoveries
                WHERE id = ? AND status = 'active' FOR UPDATE
                """).param(discoveryId).query((rs, ignored) -> rs.getTimestamp(1).toInstant()).optional()
                .orElseThrow(() -> new IllegalArgumentException("Discovery is not active."));
        if (until == null || !until.isAfter(current)) {
            throw new IllegalArgumentException("Retention extension must be later than current due date.");
        }
        jdbc.sql("""
                INSERT INTO retention_extensions (
                    id, organisation_id, discovery_id, previous_due_at, extended_until, operator_id
                ) VALUES (?, ?, ?, ?, ?, ?)
                """).params(UUID.randomUUID(), pilot.organisationId(), discoveryId,
                Timestamp.from(current), Timestamp.from(until), operator).update();
        jdbc.sql("UPDATE discoveries SET retention_due_at = ? WHERE id = ?")
                .params(Timestamp.from(until), discoveryId).update();
        tenant.auditOperator(operator, "support_retention_extended", "discovery", discoveryId);
    }

    @Transactional
    public void revokeAccess(UUID sessionId, String reasonCode) {
        var operator = operator(reasonCode);
        tenant.select();
        requireSession(sessionId);
        jdbc.sql("""
                UPDATE interview_access_grants SET revoked_at = coalesce(revoked_at, ?)
                WHERE interview_session_id = ? AND revoked_at IS NULL
                """).params(Timestamp.from(clock.instant()), sessionId).update();
        tenant.auditOperator(operator, "support_access_revoked", "interview_session", sessionId);
    }

    @Transactional
    public void terminateSession(UUID sessionId, String reasonCode) {
        var operator = operator(reasonCode);
        tenant.select();
        var session = jdbc.sql("""
                SELECT id, interview_mission_id, status, revision, active_started_at,
                       current_completion_proposal_id
                FROM interview_sessions WHERE id = ? FOR UPDATE
                """).param(sessionId).query((rs, ignored) -> new SupportSession(
                        rs.getObject("id", UUID.class), rs.getObject("interview_mission_id", UUID.class),
                        rs.getString("status"), rs.getInt("revision"),
                        rs.getTimestamp("active_started_at") == null ? null
                                : rs.getTimestamp("active_started_at").toInstant(),
                        rs.getObject("current_completion_proposal_id", UUID.class))).optional()
                .orElseThrow(() -> new IllegalArgumentException("Interview Session not found."));
        if (!Set.of("active", "paused").contains(session.status())) {
            throw new IllegalArgumentException("Only an active or paused Interview Session can be terminated.");
        }
        var now = clock.instant();
        if (session.completionProposalId() != null) {
            jdbc.sql("""
                    UPDATE interview_completion_proposals SET status = 'withdrawn', decided_at = ?
                    WHERE id = ? AND status = 'pending'
                    """).params(Timestamp.from(now), session.completionProposalId()).update();
        }
        jdbc.sql("""
                UPDATE interview_sessions SET status = 'terminated', terminated_at = ?,
                    active_seconds = active_seconds + CASE WHEN active_started_at IS NULL THEN 0
                        ELSE greatest(0, extract(epoch from (? - active_started_at))::bigint) END,
                    active_started_at = NULL, active_question_id = NULL,
                    current_completion_proposal_id = NULL, revision = revision + 1
                WHERE id = ? AND revision = ? AND status IN ('active', 'paused')
                """).params(Timestamp.from(now), Timestamp.from(now), sessionId, session.revision()).update();
        jdbc.sql("""
                UPDATE runtime_credentials SET revoked_at = coalesce(revoked_at, ?)
                WHERE interview_session_id = ? AND revoked_at IS NULL
                """).params(Timestamp.from(now), sessionId).update();
        jdbc.sql("""
                UPDATE interview_runtime_attempts SET outcome = 'cancelled', finished_at = ?
                WHERE runtime_run_id IN (SELECT id FROM interview_runtime_runs
                    WHERE interview_session_id = ? AND status = 'running') AND outcome = 'running'
                """).params(Timestamp.from(now), sessionId).update();
        jdbc.sql("""
                UPDATE interview_runtime_runs SET status = 'cancelled', cancelled_at = ?,
                    lease_until = NULL, lease_owner = NULL, heartbeat_at = NULL, updated_at = ?
                WHERE interview_session_id = ? AND status IN ('queued', 'running')
                """).params(Timestamp.from(now), Timestamp.from(now), sessionId).update();
        jdbc.sql("""
                UPDATE invitations SET revoked_at = coalesce(revoked_at, ?),
                    delivery_status = CASE WHEN delivery_status = 'legacy_inert'
                        THEN delivery_status ELSE 'revoked' END
                WHERE interview_mission_id = ? AND revoked_at IS NULL
                """).params(Timestamp.from(now), session.missionId()).update();
        jdbc.sql("UPDATE interview_access_grants SET revoked_at = coalesce(revoked_at, ?) WHERE interview_session_id = ?")
                .params(Timestamp.from(now), sessionId).update();
        tenant.auditOperator(operator, "support_session_terminated", "interview_session", sessionId);
    }

    @Transactional
    public String verifyDeletion(UUID ledgerId, String reasonCode) {
        var operator = operator(reasonCode);
        tenant.select();
        var stage = jdbc.sql("SELECT stage FROM deletion_ledger WHERE id = ?")
                .param(ledgerId).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Deletion ledger entry not found."));
        tenant.auditOperator(operator, "support_deletion_verified", "deletion_ledger", ledgerId);
        return stage;
    }

    @Transactional
    public String verifyBackup(UUID backupId, String reasonCode) {
        var operator = operator(reasonCode);
        tenant.select();
        var state = backups.verify(backupId).state();
        tenant.auditOperator(operator, "support_backup_verified", "backup", backupId);
        return state;
    }

    @Transactional
    public UUID requestBreakGlass(UUID evidenceId, String reasonCode, String reason, Instant expiresAt) {
        var operator = operator(reasonCode);
        tenant.select();
        if (!Set.of("incident_diagnosis", "data_recovery", "security_investigation").contains(reasonCode)) {
            throw new IllegalArgumentException("Break-glass reason code is invalid.");
        }
        var exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM evidence WHERE id = ?)")
                .param(evidenceId).query(Boolean.class).single();
        var now = clock.instant();
        if (!exists || expiresAt == null || !expiresAt.isAfter(now)
                || expiresAt.isAfter(now.plus(java.time.Duration.ofHours(4)))) {
            throw new IllegalArgumentException("Break-glass request scope or expiry is invalid.");
        }
        var id = UUID.randomUUID();
        var encrypted = reasons.encrypt(reason, id + ":" + evidenceId + ":" + operator);
        jdbc.sql("""
                INSERT INTO break_glass_requests (
                    id, organisation_id, evidence_id, operator_id, reason_code,
                    reason_key_id, reason_nonce, reason_ciphertext, requested_at, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, pilot.organisationId(), evidenceId, operator, reasonCode,
                encrypted.keyId(), encrypted.nonce(), encrypted.ciphertext(),
                Timestamp.from(now), Timestamp.from(expiresAt)).update();
        tenant.auditOperator(operator, "break_glass_requested", "evidence", evidenceId);
        return id;
    }

    @Transactional(readOnly = true)
    public BreakGlassReview reviewBreakGlass(UUID requestId, String investigatorEmail) {
        var investigator = tenant.investigator(investigatorEmail);
        return jdbc.sql("""
                SELECT b.id, b.evidence_id, b.operator_id, b.reason_code, b.reason_key_id,
                       b.reason_nonce, b.reason_ciphertext, b.status, b.requested_at, b.expires_at
                FROM break_glass_requests b
                JOIN evidence e ON e.id = b.evidence_id AND e.organisation_id = b.organisation_id
                JOIN discoveries d ON d.id = e.discovery_id AND d.owner_membership_id = ?
                WHERE b.id = ?
                """).params(investigator.membershipId(), requestId).query((rs, ignored) -> {
                    var evidence = rs.getObject("evidence_id", UUID.class);
                    var operator = rs.getObject("operator_id", UUID.class);
                    return new BreakGlassReview(rs.getObject("id", UUID.class), evidence, operator,
                            rs.getString("reason_code"), reasons.decrypt(rs.getString("reason_key_id"),
                            rs.getBytes("reason_nonce"), rs.getBytes("reason_ciphertext"),
                            requestId + ":" + evidence + ":" + operator), rs.getString("status"),
                            rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("expires_at").toInstant());
                }).optional().orElseThrow(() -> new IllegalArgumentException("Break-glass request not found."));
    }

    @Transactional
    public void decideBreakGlass(UUID requestId, boolean approve, String investigatorEmail) {
        var investigator = tenant.investigator(investigatorEmail);
        var updated = jdbc.sql("""
                UPDATE break_glass_requests b SET status = ?, decided_at = ?, approved_by_membership_id = ?
                FROM evidence e, discoveries d
                WHERE b.id = ? AND b.status = 'pending' AND b.expires_at > ?
                  AND e.id = b.evidence_id AND e.organisation_id = b.organisation_id
                  AND d.id = e.discovery_id AND d.owner_membership_id = ?
                """).params(approve ? "approved" : "rejected", Timestamp.from(clock.instant()),
                investigator.membershipId(), requestId, Timestamp.from(clock.instant()),
                investigator.membershipId()).update();
        if (updated != 1) throw new IllegalArgumentException("Break-glass request is stale or denied.");
        tenant.audit(investigator, approve ? "break_glass_approved" : "break_glass_rejected",
                "break_glass_request", requestId);
    }

    @Transactional
    public String viewBreakGlass(UUID requestId, String reasonCode) {
        var operator = operator(reasonCode);
        tenant.select();
        var content = jdbc.sql("""
                SELECT e.answer FROM break_glass_requests b
                JOIN evidence e ON e.id = b.evidence_id AND e.organisation_id = b.organisation_id
                WHERE b.id = ? AND b.operator_id = ? AND b.status = 'approved'
                  AND b.revoked_at IS NULL AND b.expires_at > ?
                """).params(requestId, operator, Timestamp.from(clock.instant())).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Break-glass grant is unavailable."));
        tenant.auditOperator(operator, "break_glass_content_viewed", "break_glass_request", requestId);
        return content;
    }

    @Transactional
    public void revokeBreakGlass(UUID requestId, String reasonCode) {
        var operator = operator(reasonCode);
        tenant.select();
        var changed = jdbc.sql("""
                UPDATE break_glass_requests SET status = 'revoked', revoked_at = ?
                WHERE id = ? AND operator_id = ? AND status = 'approved'
                """).params(Timestamp.from(clock.instant()), requestId, operator).update();
        if (changed != 1) throw new IllegalArgumentException("Break-glass grant is unavailable.");
        tenant.auditOperator(operator, "break_glass_revoked", "break_glass_request", requestId);
    }

    private void retryInvitation(UUID jobId) {
        var invitationId = jdbc.sql("""
                SELECT invitation_id FROM invitation_delivery_jobs
                WHERE id = ? AND status = 'failed' FOR UPDATE
                """).param(jobId).query(UUID.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Invitation work is not retryable."));
        jdbc.sql("""
                UPDATE invitation_delivery_jobs SET delivery_cycle = delivery_cycle + 1,
                    attempt_count = 0, status = 'pending', next_attempt_at = ?,
                    lease_owner = NULL, lease_expires_at = NULL, heartbeat_at = NULL,
                    execution_correlation_id = NULL, updated_at = ? WHERE id = ?
                """).params(Timestamp.from(clock.instant()), Timestamp.from(clock.instant()), jobId).update();
        jdbc.sql("UPDATE invitations SET delivery_status = 'pending', provider_message_id = NULL WHERE id = ?")
                .param(invitationId).update();
    }

    private void retryFindings(UUID runId) {
        var run = jdbc.sql("""
                SELECT organisation_id, discovery_id, interview_session_id, interview_mission_id,
                       expected_revision, generation
                FROM interview_runtime_runs
                WHERE id = ? AND work_kind = 'findings_extraction' AND status = 'failed' FOR UPDATE
                """).param(runId).query((rs, ignored) -> new FailedExtraction(
                        rs.getObject("organisation_id", UUID.class), rs.getObject("discovery_id", UUID.class),
                        rs.getObject("interview_session_id", UUID.class),
                        rs.getObject("interview_mission_id", UUID.class), rs.getInt("expected_revision"),
                        rs.getInt("generation"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("Findings work is not retryable."));
        jdbc.sql("""
                INSERT INTO interview_runtime_runs (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    work_kind, trigger, expected_revision, generation
                ) VALUES (?, ?, ?, ?, ?, 'findings_extraction', 'findings_extraction', ?, ?)
                """).params(UUID.randomUUID(), run.organisationId(), run.discoveryId(), run.sessionId(),
                run.missionId(), run.expectedRevision(), run.generation() + 1).update();
    }

    private void requireSession(UUID sessionId) {
        if (!jdbc.sql("SELECT EXISTS (SELECT 1 FROM interview_sessions WHERE id = ?)")
                .param(sessionId).query(Boolean.class).single()) {
            throw new IllegalArgumentException("Interview Session not found.");
        }
    }

    private UUID operator(String reasonCode) {
        if (!Set.of("incident_response", "customer_request", "recovery",
                "incident_diagnosis", "data_recovery", "security_investigation").contains(reasonCode)) {
            throw new IllegalArgumentException("Support reason code is invalid.");
        }
        return properties.requiredOperatorId();
    }

    private static String error(Signal signal) {
        return switch (signal.error()) {
            case UNAVAILABLE -> "unavailable";
            case TIMEOUT -> "timeout";
            case EXHAUSTED -> "exhausted";
            case CAPACITY -> "capacity";
            case STALE -> "stale";
            case FAILED -> "failed";
            case OVERDUE -> "overdue";
            case NONE -> throw new IllegalArgumentException("Alert requires a safe error class.");
        };
    }

    private static OperationalTelemetry.AlertKind alertKind(String value) {
        for (var kind : OperationalTelemetry.AlertKind.values()) if (kind.value().equals(value)) return kind;
        throw new IllegalStateException("Unknown alert kind.");
    }

    private static OperationalTelemetry.SafeError safeError(String value) {
        return OperationalTelemetry.SafeError.valueOf(value.toUpperCase());
    }

    private record Key(OperationalTelemetry.AlertKind kind, UUID resourceId) {}
    private record CurrentAlert(UUID id, OperationalTelemetry.AlertKind kind, UUID resourceId,
            OperationalTelemetry.SafeError error) {}
    public record Status(int firingAlerts, int queuedJobs) {}
    public record BreakGlassReview(UUID id, UUID evidenceId, UUID operatorId, String reasonCode,
            String reason, String status, Instant requestedAt, Instant expiresAt) {}
    private record SupportSession(UUID id, UUID missionId, String status, int revision,
            Instant activeStartedAt, UUID completionProposalId) {}
    private record FailedExtraction(UUID organisationId, UUID discoveryId, UUID sessionId,
            UUID missionId, int expectedRevision, int generation) {}
}
