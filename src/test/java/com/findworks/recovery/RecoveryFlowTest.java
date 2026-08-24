package com.findworks.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.findworks.FindWorksApplication;
import com.findworks.recovery.RecoveryBundle.ApplyRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(properties = {
        "findworks.process-role=recovery",
        "findworks.recovery.operator-id=70000000-0000-0000-0000-000000000034",
        "findworks.recovery.bundle-key-id=test-recovery-key",
        "findworks.recovery.bundle-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "findworks.recovery.application-image-digest=registry.test/findworks@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
})
@Testcontainers
class RecoveryFlowTest {

    private static final UUID ORGANISATION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID DISCOVERY = UUID.fromString("40000000-0000-0000-0000-000000000034");
    private static final UUID DELETED = UUID.fromString("40000000-0000-0000-0000-000000000035");
    private static final UUID MISSION = UUID.fromString("50000000-0000-0000-0000-000000000034");
    private static final UUID ITEM = UUID.fromString("60000000-0000-0000-0000-000000000034");
    private static final UUID PARTICIPANT = UUID.fromString("80000000-0000-0000-0000-000000000034");
    private static final UUID SESSION = UUID.fromString("90000000-0000-0000-0000-000000000034");
    private static final UUID EVIDENCE = UUID.fromString("a0000000-0000-0000-0000-000000000034");
    private static final UUID RUNTIME = UUID.fromString("b0000000-0000-0000-0000-000000000034");
    private static final UUID PACKAGE = UUID.fromString("c0000000-0000-0000-0000-000000000034");
    private static final UUID PACKAGE_VERSION = UUID.fromString("d0000000-0000-0000-0000-000000000034");
    private static final UUID KNOWLEDGE = UUID.fromString("e0000000-0000-0000-0000-000000000034");
    private static final UUID KNOWLEDGE_VERSION = UUID.fromString("f0000000-0000-0000-0000-000000000034");
    private static final UUID LEDGER = UUID.fromString("71000000-0000-0000-0000-000000000034");
    private static final String IMAGE = "registry.test/findworks@sha256:"
            + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer source = new PostgreSQLContainer("postgres:17-alpine");

    @Container
    static final PostgreSQLContainer candidate = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired JdbcClient jdbc;
    @Autowired RecoveryService recovery;

    @TempDir Path temporary;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM recovery_drill_results").update();
        jdbc.sql("DELETE FROM deletion_ledger").update();
        jdbc.sql("DELETE FROM discoveries").update();
        jdbc.sql("DELETE FROM audit_records").update();
        seedGraph();
        seedDeletedCandidate();
    }

    @Test
    void isolatedDumpPlusLatestLedgerReplayPreservesEvidenceAndPreventsResurrection() throws Exception {
        var manifest = temporary.resolve("manifest.bundle");
        recovery.exportManifest(manifest);
        var dump = temporary.resolve("candidate.dump");
        assertThat(source.execInContainer("pg_dump", "-U", source.getUsername(), "-d",
                source.getDatabaseName(), "-Fc", "-f", "/tmp/candidate.dump").getExitCode()).isZero();
        source.copyFileFromContainer("/tmp/candidate.dump", dump.toString());
        prepareCandidate(dump);

        deleteAfterRecoveryPoint();
        var ledger = temporary.resolve("ledger.bundle");
        recovery.exportLedger(ledger, "ledger-restore-34", "timeline-9");

        try (var restored = candidateContext()) {
            var candidateRecovery = restored.getBean(RecoveryService.class);
            var candidateRepository = restored.getBean(RecoveryRepository.class);
            var candidateJdbc = restored.getBean(JdbcClient.class);
            var now = Instant.now();
            var request = new ApplyRequest("synthetic", "synthetic-backup-34", "candidate-restore-34",
                    "ledger-restore-34", "timeline-9", now.minusSeconds(600), now.minusSeconds(300),
                    now.minusSeconds(300), null, null, null, null, null);

            var drill = candidateRecovery.apply(ledger, manifest, request);

            assertThat(candidateJdbc.sql("SELECT count(*) FROM discoveries WHERE id = ?")
                    .param(DELETED).query(Integer.class).single()).isZero();
            assertThat(candidateJdbc.sql("SELECT answer FROM evidence WHERE id = ?")
                    .param(EVIDENCE).query(String.class).single()).isEqualTo("Retry after review.");
            assertThat(candidateJdbc.sql("SELECT quotation FROM knowledge_item_evidence_citations")
                    .query(String.class).single()).isEqualTo("Retry");
            assertThat(candidateJdbc.sql("SELECT outcome FROM recovery_drill_results WHERE id = ?")
                    .param(drill).query(String.class).single()).isEqualTo("verified_synthetic");
            assertThatThrownBy(candidateRepository::requireTrafficGate)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("provider recovery drill");

            var tampered = temporary.resolve("tampered.bundle");
            var bytes = Files.readAllBytes(ledger);
            bytes[bytes.length / 2] ^= 1;
            Files.write(tampered, bytes);
            assertThatThrownBy(() -> candidateRecovery.apply(tampered, manifest, request))
                    .isInstanceOf(RecoveryFailure.class).hasMessageContaining("bundle_integrity");
            assertThat(candidateJdbc.sql("""
                    SELECT count(*) FROM recovery_drill_results
                    WHERE outcome = 'failed' AND safe_failure_class = 'bundle_integrity'
                    """).query(Integer.class).single()).isEqualTo(1);
        }
    }

    private ConfigurableApplicationContext candidateContext() {
        return new SpringApplicationBuilder(FindWorksApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--spring.datasource.url=" + candidate.getJdbcUrl(),
                        "--spring.datasource.username=" + candidate.getUsername(),
                        "--spring.datasource.password=" + candidate.getPassword(),
                        "--spring.flyway.enabled=false",
                        "--findworks.process-role=recovery",
                        "--findworks.recovery.require-verified-gate=true",
                        "--findworks.recovery.operator-id=70000000-0000-0000-0000-000000000034",
                        "--findworks.recovery.bundle-key-id=test-recovery-key",
                        "--findworks.recovery.bundle-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                        "--findworks.recovery.application-image-digest=" + IMAGE);
    }

    private void prepareCandidate(Path dump) throws Exception {
        try (var connection = DriverManager.getConnection(candidate.getJdbcUrl(),
                candidate.getUsername(), candidate.getPassword()); var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE findworks_application NOLOGIN NOBYPASSRLS");
            statement.execute("CREATE ROLE findworks_worker NOLOGIN NOBYPASSRLS");
            statement.execute("CREATE ROLE findworks_support NOLOGIN NOBYPASSRLS");
            statement.execute("CREATE ROLE findworks_recovery NOLOGIN NOBYPASSRLS");
        }
        candidate.copyFileToContainer(MountableFile.forHostPath(dump), "/tmp/candidate.dump");
        var restored = candidate.execInContainer("pg_restore", "-U", candidate.getUsername(), "-d",
                candidate.getDatabaseName(), "--single-transaction", "--no-owner",
                "--exit-on-error", "/tmp/candidate.dump");
        assertThat(restored.getExitCode()).withFailMessage(restored.getStderr()).isZero();
    }

    private void deleteAfterRecoveryPoint() {
        var now = Instant.now();
        jdbc.sql("""
                UPDATE discoveries SET status = 'deletion_pending', access_blocked_at = ?, purge_due_at = ?
                WHERE id = ?
                """).params(Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.minusSeconds(30)), DELETED).update();
        jdbc.sql("""
                INSERT INTO deletion_ledger (
                    id, organisation_id, target_kind, target_id, requested_by_kind, stage, attempts,
                    requested_at, access_blocked_at, purge_deadline, available_at,
                    completed_at, backup_expiry_due_at
                ) VALUES (?, ?, 'discovery', ?, 'system', 'completed', 1, ?, ?, ?, ?, ?, ?)
                """).params(LEDGER, ORGANISATION, DELETED, Timestamp.from(now.minusSeconds(90)),
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now.minusSeconds(30)),
                Timestamp.from(now.minusSeconds(60)), Timestamp.from(now),
                Timestamp.from(now.plusSeconds(30L * 86_400))).update();
        jdbc.sql("DELETE FROM discoveries WHERE id = ?").param(DELETED).update();
    }

    private void seedDeletedCandidate() {
        jdbc.sql("""
                INSERT INTO discoveries (
                    id, organisation_id, owner_membership_id, title, objective, retention_due_at)
                VALUES (?, ?, ?, 'Historical deletion target', 'Must not return', now() + interval '90 days')
                """).params(DELETED, ORGANISATION, OWNER).update();
    }

    private void seedGraph() {
        jdbc.sql("""
                INSERT INTO discoveries (
                    id, organisation_id, owner_membership_id, title, objective, retention_due_at)
                VALUES (?, ?, ?, 'Retained fixture', 'Prove restore graph', now() + interval '90 days')
                """).params(DISCOVERY, ORGANISATION, OWNER).update();
        jdbc.sql("""
                INSERT INTO interview_missions (
                    id, organisation_id, discovery_id, lineage_id, version, status,
                    interviewee_name, interviewee_email, objective, desired_outcome,
                    interviewee_relevance, completion_criteria, expected_commitment,
                    data_use_summary, approved_at
                ) VALUES (?, ?, ?, ?, 1, 'approved', 'Synthetic participant', NULL,
                    'Recover evidence', 'Verified graph', 'Synthetic fixture', 'Evidence resolves',
                    '10 minutes', 'Synthetic data only', now())
                """).params(MISSION, ORGANISATION, DISCOVERY, MISSION).update();
        jdbc.sql("""
                INSERT INTO investigation_items (
                    id, organisation_id, interview_mission_id, position, knowledge_gap,
                    required, importance, priority, relevant_context)
                VALUES (?, ?, ?, 0, 'Retry rule', true, 'Restore proof', 'high', 'Synthetic')
                """).params(ITEM, ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO discovery_participants (id, organisation_id, discovery_id, intended_name, email)
                VALUES (?, ?, ?, 'Synthetic participant', 'synthetic-recovery@example.test')
                """).params(PARTICIPANT, ORGANISATION, DISCOVERY).update();
        jdbc.sql("""
                INSERT INTO interview_sessions (
                    id, organisation_id, discovery_id, interview_mission_id, participant_id)
                VALUES (?, ?, ?, ?, ?)
                """).params(SESSION, ORGANISATION, DISCOVERY, MISSION, PARTICIPANT).update();
        jdbc.sql("""
                INSERT INTO evidence (
                    id, organisation_id, discovery_id, interview_session_id,
                    interview_mission_id, participant_id, investigation_item_id,
                    source_type, answer)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'legacy', 'Retry after review.')
                """).params(EVIDENCE, ORGANISATION, DISCOVERY, SESSION, MISSION, PARTICIPANT, ITEM).update();
        jdbc.sql("""
                INSERT INTO interview_runtime_runs (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    trigger, expected_revision, status, work_kind, generation)
                VALUES (?, ?, ?, ?, ?, 'findings_extraction', 1, 'committed', 'findings_extraction', 1)
                """).params(RUNTIME, ORGANISATION, DISCOVERY, SESSION, MISSION).update();
        jdbc.sql("""
                INSERT INTO findings_packages (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id)
                VALUES (?, ?, ?, ?, ?)
                """).params(PACKAGE, ORGANISATION, DISCOVERY, SESSION, MISSION).update();
        jdbc.sql("""
                INSERT INTO findings_package_versions (
                    id, organisation_id, discovery_id, findings_package_id,
                    interview_session_id, interview_mission_id, runtime_run_id, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                """).params(PACKAGE_VERSION, ORGANISATION, DISCOVERY, PACKAGE, SESSION, MISSION, RUNTIME).update();
        jdbc.sql("""
                INSERT INTO knowledge_items (
                    id, organisation_id, discovery_id, interview_session_id,
                    interview_mission_id, investigation_item_id, findings_package_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """).params(KNOWLEDGE, ORGANISATION, DISCOVERY, SESSION, MISSION, ITEM, PACKAGE).update();
        jdbc.sql("""
                INSERT INTO knowledge_item_versions (
                    id, organisation_id, knowledge_item_id, findings_package_version_id,
                    investigation_item_id, interview_session_id, interview_mission_id,
                    version, category, claim, confirmation_state)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'rule', 'Retry requires review.', 'unreviewed')
                """).params(KNOWLEDGE_VERSION, ORGANISATION, KNOWLEDGE, PACKAGE_VERSION,
                ITEM, SESSION, MISSION).update();
        jdbc.sql("""
                INSERT INTO knowledge_item_evidence_citations (
                    organisation_id, knowledge_item_version_id, investigation_item_id,
                    interview_session_id, interview_mission_id, evidence_id,
                    position, start_offset, end_offset, quotation)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, 5, 'Retry')
                """).params(ORGANISATION, KNOWLEDGE_VERSION, ITEM, SESSION, MISSION, EVIDENCE).update();
    }
}
