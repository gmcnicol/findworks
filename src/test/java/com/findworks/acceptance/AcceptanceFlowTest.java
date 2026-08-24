package com.findworks.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.findworks.acceptance.AcceptanceRepository.LiveBinding;
import com.findworks.acceptance.AcceptanceRepository.ReleaseManifest;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "findworks.process-role=acceptance",
        "findworks.operations.operator-id=70000000-0000-0000-0000-000000000035",
        "findworks.acceptance.release-digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "findworks.acceptance.git-commit=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "findworks.acceptance.skill-digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "findworks.acceptance.extension-digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "findworks.acceptance.traceability-digest=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "findworks.runtime.image=registry.test/pi@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "findworks.runtime.provider=provider-alias",
        "findworks.runtime.model=model-alias"
})
@Testcontainers
class AcceptanceFlowTest {

    private static final UUID ORGANISATION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID DISCOVERY = UUID.fromString("40000000-0000-0000-0000-000000000035");
    private static final UUID MISSION = UUID.fromString("50000000-0000-0000-0000-000000000035");
    private static final UUID ITEM = UUID.fromString("60000000-0000-0000-0000-000000000035");
    private static final UUID BOUNDARY = UUID.fromString("61000000-0000-0000-0000-000000000035");
    private static final UUID PARTICIPANT = UUID.fromString("80000000-0000-0000-0000-000000000035");
    private static final UUID SESSION = UUID.fromString("90000000-0000-0000-0000-000000000035");
    private static final UUID EVIDENCE = UUID.fromString("a0000000-0000-0000-0000-000000000035");
    private static final UUID TURN = UUID.fromString("b0000000-0000-0000-0000-000000000035");
    private static final UUID EXTRACTION = UUID.fromString("b1000000-0000-0000-0000-000000000035");
    private static final UUID PROPOSAL = UUID.fromString("b2000000-0000-0000-0000-000000000035");
    private static final UUID PACKAGE = UUID.fromString("c0000000-0000-0000-0000-000000000035");
    private static final UUID PACKAGE_VERSION = UUID.fromString("d0000000-0000-0000-0000-000000000035");
    private static final UUID REVIEW = UUID.fromString("d1000000-0000-0000-0000-000000000035");
    private static final UUID KNOWLEDGE = UUID.fromString("e0000000-0000-0000-0000-000000000035");
    private static final UUID KNOWLEDGE_VERSION = UUID.fromString("f0000000-0000-0000-0000-000000000035");
    private static final UUID RESTORE = UUID.fromString("72000000-0000-0000-0000-000000000035");
    private static final String DIGEST = "sha256:"
            + "a".repeat(64);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired JdbcClient jdbc;
    @Autowired AcceptanceRepository acceptance;

    @BeforeEach
    void setUp() {
        seedJourney();
        seedRestore();
    }

    @Test
    void frozenJourneyPassesButOutOfScopeProvenanceAutomaticallyFails() {
        var release = new ReleaseManifest(DIGEST, "a".repeat(40), acceptance.currentMigrationDigest(),
                DIGEST, DIGEST, DIGEST,
                "provider-alias", "model-alias", DIGEST);
        var checks = AcceptanceRepository.SCRIPTED_CHECKS.stream()
                .collect(Collectors.toMap(value -> value, ignored -> "passed"));

        var failedScripted = acceptance.recordScripted(release, RESTORE,
                Map.of("S01-SHAPING", "passed"), DIGEST);
        assertThat(failedScripted.passed()).isFalse();
        assertThatThrownBy(() -> acceptance.openLive(failedScripted.id(), RESTORE))
                .hasMessageContaining("Passing scripted evidence");

        var scripted = acceptance.recordScripted(release, RESTORE, checks, DIGEST);
        assertThat(scripted.passed()).isTrue();
        var live = acceptance.openLive(scripted.id(), RESTORE);
        assertThat(acceptance.verifyLive(binding(live))).isTrue();
        assertThat(acceptance.finalise(live)).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM m0_acceptance_results WHERE run_id = ?")
                .param(live).query(Integer.class).single()).isEqualTo(9);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM m0_acceptance_runs WHERE id = ?")
                .param(live).update()).hasMessageContaining("append-only");

        jdbc.sql("""
                INSERT INTO evidence_scope_assessments (
                    id, organisation_id, interview_session_id, interview_mission_id,
                    investigation_item_id, evidence_id, runtime_run_id, assessment,
                    mission_boundary_id, rationale)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, 'out_of_scope', ?, 'Synthetic denial')
                """).params(ORGANISATION, SESSION, MISSION, ITEM, EVIDENCE, TURN, BOUNDARY).update();
        var secondScripted = acceptance.recordScripted(release, RESTORE, checks, DIGEST);
        var secondLive = acceptance.openLive(secondScripted.id(), RESTORE);
        assertThat(acceptance.verifyLive(binding(secondLive))).isFalse();
        assertThat(jdbc.sql("""
                SELECT outcome || ':' || safe_failure_class FROM m0_acceptance_results
                WHERE run_id = ? AND criterion = 'AC35-6'
                """).param(secondLive).query(String.class).single())
                .isEqualTo("failed:automatic_failure");
    }

    private LiveBinding binding(UUID run) {
        return new LiveBinding(run, DISCOVERY, MISSION, SESSION, PACKAGE_VERSION,
                OWNER, PARTICIPANT, OWNER, true, true, true, true);
    }

    private void seedJourney() {
        jdbc.sql("""
                INSERT INTO discoveries (
                    id, organisation_id, owner_membership_id, title, objective, retention_due_at)
                VALUES (?, ?, ?, 'Synthetic acceptance', 'Prove the frozen graph', now() + interval '90 days')
                """).params(DISCOVERY, ORGANISATION, OWNER).update();
        jdbc.sql("""
                INSERT INTO interview_missions (
                    id, organisation_id, discovery_id, lineage_id, version, status,
                    interviewee_name, interviewee_email, objective, desired_outcome,
                    interviewee_relevance, completion_criteria, expected_commitment,
                    data_use_summary, approved_at)
                VALUES (?, ?, ?, ?, 1, 'approved', 'Synthetic expert', NULL,
                    'Learn retry rules', 'Reviewed findings', 'Owns retry policy',
                    'Required rule resolved', '10 minutes', 'Synthetic data only', now())
                """).params(MISSION, ORGANISATION, DISCOVERY, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_boundaries (
                    id, organisation_id, interview_mission_id, position, boundary_kind, content)
                VALUES (?, ?, ?, 0, 'boundary', 'Retry policy only')
                """).params(BOUNDARY, ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO investigation_items (
                    id, organisation_id, interview_mission_id, position, knowledge_gap,
                    required, importance, priority, relevant_context)
                VALUES (?, ?, ?, 0, 'Retry rule', true, 'Required proof', 'high', 'Synthetic')
                """).params(ITEM, ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO discovery_participants (id, organisation_id, discovery_id, intended_name, email)
                VALUES (?, ?, ?, 'Synthetic expert', 'synthetic-acceptance@example.test')
                """).params(PARTICIPANT, ORGANISATION, DISCOVERY).update();
        jdbc.sql("""
                INSERT INTO invitations (
                    id, interview_mission_id, token_hash, expires_at, redeemed_at,
                    organisation_id, participant_id, recipient_email, token_key_id,
                    send_confirmed_at, delivery_status, provider_message_id, discovery_id)
                VALUES (gen_random_uuid(), ?, ?, now() + interval '7 days', now(),
                    ?, ?, 'synthetic-acceptance@example.test', 'test-key', now(),
                    'redeemed', 'provider-message-35', ?)
                """).params(MISSION, "1".repeat(64), ORGANISATION, PARTICIPANT, DISCOVERY).update();
        jdbc.sql("""
                INSERT INTO interview_sessions (
                    id, interview_mission_id, status, started_at, completed_at,
                    organisation_id, discovery_id, participant_id, revision,
                    active_seconds, commitment_acknowledged_at)
                VALUES (?, ?, 'completed', now() - interval '10 minutes', now(),
                    ?, ?, ?, 3, 600, now() - interval '10 minutes')
                """).params(SESSION, MISSION, ORGANISATION, DISCOVERY, PARTICIPANT).update();
        jdbc.sql("""
                INSERT INTO evidence (
                    id, interview_session_id, investigation_item_id, answer,
                    organisation_id, discovery_id, interview_mission_id,
                    participant_id, source_type)
                VALUES (?, ?, ?, 'Retry after review.', ?, ?, ?, ?, 'legacy')
                """).params(EVIDENCE, SESSION, ITEM, ORGANISATION, DISCOVERY, MISSION, PARTICIPANT).update();
        jdbc.sql("""
                INSERT INTO interview_runtime_runs (
                    id, organisation_id, discovery_id, interview_session_id,
                    interview_mission_id, trigger, expected_revision, status,
                    work_kind, generation)
                VALUES (?, ?, ?, ?, ?, 'session_start', 1, 'committed', 'interview_turn', 1),
                       (?, ?, ?, ?, ?, 'findings_extraction', 3, 'committed', 'findings_extraction', 1)
                """).params(TURN, ORGANISATION, DISCOVERY, SESSION, MISSION,
                EXTRACTION, ORGANISATION, DISCOVERY, SESSION, MISSION).update();
        jdbc.sql("""
                INSERT INTO interview_completion_proposals (
                    id, organisation_id, discovery_id, interview_session_id,
                    interview_mission_id, runtime_run_id, proposed_revision,
                    recap, status, decided_at)
                VALUES (?, ?, ?, ?, ?, ?, 2, 'Required rule resolved.', 'confirmed', now())
                """).params(PROPOSAL, ORGANISATION, DISCOVERY, SESSION, MISSION, TURN).update();
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
                """).params(PACKAGE_VERSION, ORGANISATION, DISCOVERY, PACKAGE, SESSION, MISSION, EXTRACTION).update();
        jdbc.sql("""
                INSERT INTO findings_package_results (
                    organisation_id, findings_package_version_id, interview_session_id,
                    interview_mission_id, investigation_item_id, position, required)
                VALUES (?, ?, ?, ?, ?, 0, true)
                """).params(ORGANISATION, PACKAGE_VERSION, SESSION, MISSION, ITEM).update();
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
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, 'rule', 'Retry after review.', 'accepted')
                """).params(KNOWLEDGE_VERSION, ORGANISATION, KNOWLEDGE, PACKAGE_VERSION,
                ITEM, SESSION, MISSION).update();
        jdbc.sql("""
                INSERT INTO knowledge_item_evidence_citations (
                    organisation_id, knowledge_item_version_id, investigation_item_id,
                    interview_session_id, interview_mission_id, evidence_id,
                    position, start_offset, end_offset, quotation)
                VALUES (?, ?, ?, ?, ?, ?, 0, 0, 5, 'Retry')
                """).params(ORGANISATION, KNOWLEDGE_VERSION, ITEM, SESSION, MISSION, EVIDENCE).update();
        jdbc.sql("""
                INSERT INTO findings_package_reviews (
                    id, organisation_id, findings_package_version_id,
                    interview_session_id, interview_mission_id, review_revision)
                VALUES (?, ?, ?, ?, ?, 1)
                """).params(REVIEW, ORGANISATION, PACKAGE_VERSION, SESSION, MISSION).update();
        jdbc.sql("""
                INSERT INTO knowledge_item_reviews (
                    id, organisation_id, findings_package_version_id, knowledge_item_id,
                    knowledge_item_version_id, state, actor_membership_id)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, 'accepted', ?)
                """).params(ORGANISATION, PACKAGE_VERSION, KNOWLEDGE, KNOWLEDGE_VERSION, OWNER).update();
        jdbc.sql("""
                INSERT INTO findings_package_decisions (
                    id, organisation_id, findings_package_review_id,
                    findings_package_version_id, interview_session_id,
                    interview_mission_id, decision, notes, actor_membership_id, review_revision)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'accepted',
                    'Reviewed against exact Evidence.', ?, 1)
                """).params(ORGANISATION, REVIEW, PACKAGE_VERSION, SESSION, MISSION, OWNER).update();
    }

    private void seedRestore() {
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
                    duration_seconds, outcome, completed_at)
                VALUES (?, ?, ?, 'provider', 'backup-35', 'candidate-35', 'ledger-35',
                    now(), now(), 'timeline-35', now(), ?, 22, true, true, 60, 24, 30,
                    0, 0, 1, 1, true, true, true, true, 60, 'ready', now())
                """).params(RESTORE, ORGANISATION,
                UUID.fromString("70000000-0000-0000-0000-000000000035"),
                "registry.test/findworks@" + DIGEST).update();
    }
}
