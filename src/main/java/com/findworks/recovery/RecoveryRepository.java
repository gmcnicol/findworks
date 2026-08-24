package com.findworks.recovery;

import com.findworks.PilotProperties;
import com.findworks.operations.BackupVerifier;
import com.findworks.operations.OperationalSignalSource;
import com.findworks.operations.OperationalTelemetry;
import com.findworks.recovery.RecoveryBundle.ApplyRequest;
import com.findworks.recovery.RecoveryBundle.CitationExpectation;
import com.findworks.recovery.RecoveryBundle.EvidenceExpectation;
import com.findworks.recovery.RecoveryBundle.Manifest;
import com.findworks.retention.RetentionRepository.RecoveryDeletion;
import com.findworks.security.PilotTenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class RecoveryRepository implements BackupVerifier, OperationalSignalSource {

    private final JdbcClient jdbc;
    private final PilotTenant tenant;
    private final PilotProperties pilot;
    private final RecoveryProperties properties;
    private final Clock clock;

    RecoveryRepository(JdbcClient jdbc, PilotTenant tenant, PilotProperties pilot,
            RecoveryProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.pilot = pilot;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<RecoveryDeletion> exportLedger() {
        tenant.select();
        return jdbc.sql("""
                SELECT id, organisation_id, target_kind, target_id, requested_by_kind,
                       requested_by_id, stage, attempts, requested_at, access_blocked_at,
                       purge_deadline, available_at, completed_at, backup_expiry_due_at,
                       safe_error_class
                FROM deletion_ledger ORDER BY requested_at, id
                """).query((rs, ignored) -> new RecoveryDeletion(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getString("target_kind"), rs.getObject("target_id", UUID.class),
                        rs.getString("requested_by_kind"), rs.getObject("requested_by_id", UUID.class),
                        rs.getString("stage"), rs.getInt("attempts"),
                        instant(rs, "requested_at"), instant(rs, "access_blocked_at"),
                        instant(rs, "purge_deadline"), instant(rs, "available_at"),
                        nullableInstant(rs, "completed_at"), nullableInstant(rs, "backup_expiry_due_at"),
                        rs.getString("safe_error_class"))).list();
    }

    @Transactional(readOnly = true)
    public Manifest exportManifest() {
        tenant.select();
        var evidence = jdbc.sql("SELECT id, answer FROM evidence ORDER BY id")
                .query((rs, ignored) -> new EvidenceExpectation(
                        rs.getObject("id", UUID.class), sha256(rs.getString("answer")))).list();
        var citations = jdbc.sql("""
                SELECT knowledge_item_version_id, evidence_id, start_offset, end_offset, quotation
                FROM knowledge_item_evidence_citations
                ORDER BY knowledge_item_version_id, position
                """).query((rs, ignored) -> new CitationExpectation(
                        rs.getObject("knowledge_item_version_id", UUID.class),
                        rs.getObject("evidence_id", UUID.class), rs.getInt("start_offset"),
                        rs.getInt("end_offset"), sha256(rs.getString("quotation")))).list();
        return new Manifest(1, evidence, citations);
    }

    @Transactional(readOnly = true)
    public Verification verify(Manifest manifest) {
        tenant.select();
        var evidenceChecks = 0;
        for (var expected : manifest.evidence()) {
            var answer = jdbc.sql("SELECT answer FROM evidence WHERE id = ?")
                    .param(expected.evidenceId()).query(String.class).optional()
                    .orElseThrow(() -> new RecoveryFailure("graph_invalid"));
            if (!sha256(answer).equals(expected.sha256())) throw new RecoveryFailure("graph_invalid");
            evidenceChecks++;
        }
        var citationChecks = 0;
        for (var expected : manifest.citations()) {
            var citation = jdbc.sql("""
                    SELECT quotation, start_offset, end_offset
                    FROM knowledge_item_evidence_citations
                    WHERE knowledge_item_version_id = ? AND evidence_id = ?
                      AND start_offset = ? AND end_offset = ?
                    """).params(expected.knowledgeItemVersionId(), expected.evidenceId(),
                    expected.startOffset(), expected.endOffset())
                    .query((rs, ignored) -> rs.getString("quotation")).optional()
                    .orElseThrow(() -> new RecoveryFailure("graph_invalid"));
            if (!sha256(citation).equals(expected.quotationSha256())) {
                throw new RecoveryFailure("graph_invalid");
            }
            citationChecks++;
        }
        var graphInvalid = count("""
                SELECT count(*) FROM knowledge_item_evidence_citations c
                JOIN evidence e ON e.id = c.evidence_id AND e.organisation_id = c.organisation_id
                WHERE substring(e.answer FROM c.start_offset + 1 FOR c.end_offset - c.start_offset)
                    <> c.quotation
                """) + count("""
                SELECT count(*) FROM knowledge_items k
                WHERE NOT EXISTS (SELECT 1 FROM knowledge_item_versions v
                    WHERE v.knowledge_item_id = k.id AND v.version = k.current_version)
                """);
        var accessInvalid = count("""
                SELECT count(*) FROM interview_access_grants g
                JOIN interview_sessions s ON s.id = g.interview_session_id
                JOIN discoveries d ON d.id = s.discovery_id
                WHERE g.revoked_at IS NULL AND (s.access_blocked_at IS NOT NULL OR d.access_blocked_at IS NOT NULL)
                """) + count("""
                SELECT count(*) FROM invitations i JOIN discoveries d ON d.id = i.discovery_id
                WHERE i.revoked_at IS NULL AND d.access_blocked_at IS NOT NULL
                """) + count("""
                SELECT count(*) FROM runtime_credentials c JOIN discoveries d ON d.id = c.discovery_id
                WHERE c.revoked_at IS NULL AND d.access_blocked_at IS NOT NULL
                """);
        var retentionInvalid = count("""
                SELECT count(*) FROM discoveries
                WHERE (status = 'active' AND (retention_due_at IS NULL OR access_blocked_at IS NOT NULL))
                   OR (status = 'deletion_pending' AND (access_blocked_at IS NULL OR purge_due_at IS NULL))
                """);
        var deletionInvalid = count("""
                SELECT count(*) FROM deletion_ledger l
                WHERE (l.stage = 'completed' AND (
                    (l.target_kind = 'discovery' AND EXISTS (
                        SELECT 1 FROM discoveries d WHERE d.id = l.target_id))
                    OR (l.target_kind = 'interview_session' AND EXISTS (
                        SELECT 1 FROM interview_sessions s WHERE s.id = l.target_id))))
                   OR (l.stage <> 'completed' AND (
                    (l.target_kind = 'discovery' AND EXISTS (
                        SELECT 1 FROM discoveries d WHERE d.id = l.target_id
                          AND d.access_blocked_at IS NULL))
                    OR (l.target_kind = 'interview_session' AND EXISTS (
                        SELECT 1 FROM interview_sessions s WHERE s.id = l.target_id
                          AND s.access_blocked_at IS NULL))))
                """);
        var rlsInvalid = jdbc.sql("""
                SELECT count(*) FROM pg_class
                WHERE relname IN ('discoveries', 'interview_sessions', 'evidence',
                    'knowledge_items', 'knowledge_item_versions',
                    'knowledge_item_evidence_citations', 'deletion_ledger')
                  AND (NOT relrowsecurity OR NOT relforcerowsecurity)
                """).query(Integer.class).single();
        return new Verification(evidenceChecks, citationChecks, graphInvalid + rlsInvalid == 0,
                accessInvalid == 0, retentionInvalid == 0, deletionInvalid == 0);
    }

    @Transactional
    public UUID record(ApplyRequest request, Instant ledgerHighWater, int ledgerCount,
            int replayedCount, Verification verification, int durationSeconds, String outcome,
            String safeFailureClass) {
        tenant.select();
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO recovery_drill_results (
                    id, organisation_id, operator_id, source_kind, backup_id,
                    candidate_restore_id, ledger_restore_id, requested_restore_at,
                    achieved_restore_at, source_timeline, ledger_high_water_at,
                    application_image_digest, schema_version, encrypted_at_rest,
                    encrypted_in_transit, recovery_point_gap_seconds, snapshot_interval_hours,
                    backup_retention_days, ledger_entry_count, replayed_entry_count,
                    evidence_check_count, provenance_check_count, graph_verified,
                    access_denial_verified, retention_verified, deletion_verified,
                    duration_seconds, outcome, safe_failure_class, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, pilot.organisationId(), properties.requiredOperatorId(), request.sourceKind(),
                request.backupId(), request.candidateRestoreId(), request.expectedLedgerRestoreId(),
                timestamp(request.requestedRestoreAt()), timestamp(request.achievedRestoreAt()),
                request.expectedSourceTimeline(), ledgerHighWater == null ? null : timestamp(ledgerHighWater),
                properties.requiredImageDigest(), schemaVersion(), request.encryptedAtRest(),
                request.encryptedInTransit(), request.recoveryPointGapSeconds(),
                request.snapshotIntervalHours(), request.backupRetentionDays(), ledgerCount,
                replayedCount, verification.evidenceChecks(), verification.provenanceChecks(),
                verification.graphVerified(), verification.accessVerified(), verification.retentionVerified(),
                verification.deletionVerified(), durationSeconds, outcome, safeFailureClass,
                timestamp(clock.instant())).update();
        tenant.auditOperator(properties.requiredOperatorId(), "recovery_drill_recorded", "recovery_drill", id);
        return id;
    }

    @Transactional(readOnly = true)
    public void requireTrafficGate() {
        if (!properties.requireVerifiedGate()) return;
        tenant.select();
        var ready = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM recovery_drill_results
                    WHERE source_kind = 'provider' AND outcome = 'ready'
                      AND application_image_digest = ?
                      AND schema_version = (SELECT max(version::integer)
                        FROM flyway_schema_history WHERE success))
                """).param(properties.requiredImageDigest()).query(Boolean.class).single();
        if (!ready) throw new IllegalStateException("Verified provider recovery drill is required before traffic.");
    }

    @Override
    @Transactional(readOnly = true)
    public BackupVerifier.Result verify(UUID drillId) {
        tenant.select();
        return jdbc.sql("""
                SELECT source_kind, outcome, completed_at FROM recovery_drill_results WHERE id = ?
                """).param(drillId).query((rs, ignored) -> {
                    if (!"provider".equals(rs.getString("source_kind"))) return new BackupVerifier.Result("unavailable");
                    if ("failed".equals(rs.getString("outcome"))) return new BackupVerifier.Result("failed");
                    var stale = rs.getTimestamp("completed_at").toInstant()
                            .isBefore(clock.instant().minus(properties.maximumDrillAge()));
                    return new BackupVerifier.Result(stale ? "stale" : "verified");
                }).optional().orElseGet(() -> new BackupVerifier.Result("unavailable"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Signal> current() {
        if (!properties.backupRequired()) return List.of();
        tenant.select();
        var rows = jdbc.sql("""
                SELECT id, outcome, completed_at FROM recovery_drill_results
                WHERE source_kind = 'provider' ORDER BY completed_at DESC LIMIT 1
                """).query((rs, ignored) -> {
                    var id = rs.getObject("id", UUID.class);
                    if ("failed".equals(rs.getString("outcome"))) return new Signal(
                            OperationalTelemetry.AlertKind.BACKUP_FAILED,
                            OperationalTelemetry.SafeError.FAILED, id);
                    if (rs.getTimestamp("completed_at").toInstant()
                            .isBefore(clock.instant().minus(properties.maximumDrillAge()))) return new Signal(
                            OperationalTelemetry.AlertKind.BACKUP_STALE,
                            OperationalTelemetry.SafeError.STALE, id);
                    return new Signal(OperationalTelemetry.AlertKind.BACKUP_STALE,
                            OperationalTelemetry.SafeError.NONE, null);
                }).list();
        if (rows.isEmpty()) return List.of(new Signal(
                        OperationalTelemetry.AlertKind.BACKUP_STALE,
                        OperationalTelemetry.SafeError.UNAVAILABLE, null));
        return rows.getFirst().error() == OperationalTelemetry.SafeError.NONE
                ? List.of() : List.of(rows.getFirst());
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private int schemaVersion() {
        return jdbc.sql("SELECT max(version::integer) FROM flyway_schema_history WHERE success")
                .query(Integer.class).single();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    public record Verification(int evidenceChecks, int provenanceChecks, boolean graphVerified,
            boolean accessVerified, boolean retentionVerified, boolean deletionVerified) {
        static Verification failed() {
            return new Verification(0, 0, false, false, false, false);
        }
    }
}
