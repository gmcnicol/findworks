package com.findworks.acceptance;

import com.findworks.PilotProperties;
import com.findworks.operations.OperationsProperties;
import com.findworks.recovery.RecoveryProperties;
import com.findworks.runtime.RuntimeProperties;
import com.findworks.security.PilotTenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.HexFormat;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AcceptanceRepository {

    public static final Set<String> SCRIPTED_CHECKS = Set.of(
            "S01-SHAPING", "S02-ADAPTIVE-INTERVIEW", "B01-UNKNOWN", "B02-DECLINE",
            "B03-OWNER", "B04-CONTRADICTION", "B05-REVISION", "B06-ASSUMPTION",
            "B07-INVITATION", "B08-OUT-OF-SCOPE", "F01-INTERRUPTION", "F02-MODEL",
            "F03-PI-DEATH", "F04-EXTRACTION", "I01-SCOPE", "I02-COMPLETION",
            "O01-OPERATIONS", "R01-RESTORE");
    public static final Set<String> LIVE_CHECKS = Set.of(
            "LIVE-01", "LIVE-INTERVIEW-RUBRIC", "LIVE-FINDINGS-RUBRIC");
    private static final List<String> CRITERIA = List.of(
            "AC35-1", "AC35-2", "AC35-3", "AC35-4",
            "AC35-5", "AC35-6", "AC35-7", "AC35-8");

    private final JdbcClient jdbc;
    private final PilotTenant tenant;
    private final PilotProperties pilot;
    private final OperationsProperties operations;
    private final RecoveryProperties recovery;
    private final RuntimeProperties runtime;
    private final AcceptanceProperties expected;
    private final Clock clock;

    AcceptanceRepository(JdbcClient jdbc, PilotTenant tenant, PilotProperties pilot,
            OperationsProperties operations, RecoveryProperties recovery, RuntimeProperties runtime,
            AcceptanceProperties expected, Clock clock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.pilot = pilot;
        this.operations = operations;
        this.recovery = recovery;
        this.runtime = runtime;
        this.expected = expected;
        this.clock = clock;
    }

    @Transactional
    public ScriptedRecord recordScripted(ReleaseManifest release, UUID restoreDrillId,
            Map<String, CheckEvidence> checks, List<StoryEvidence> stories, String resultsDigest) {
        tenant.select();
        validateReleaseShape(release);
        digest(resultsDigest);
        var traceabilityFailure = traceabilityFailure(checks, stories);
        var failure = releaseMatches(release)
                ? scriptedFailure(release, restoreDrillId, checks, traceabilityFailure) : "release_mismatch";
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO m0_scripted_acceptance_runs (
                    id, organisation_id, operator_id, release_digest, git_commit,
                    migration_digest, pi_runtime_digest, skill_digest, extension_digest,
                    provider_alias, model_alias, traceability_digest, results_digest,
                    outcome, safe_failure_class, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, pilot.organisationId(), operations.requiredOperatorId(), release.releaseDigest(),
                release.gitCommit(), release.migrationDigest(), release.piRuntimeDigest(), release.skillDigest(),
                release.extensionDigest(), safe(release.providerAlias()), safe(release.modelAlias()),
                release.traceabilityDigest(), resultsDigest, failure == null ? "passed" : "failed", failure,
                Timestamp.from(clock.instant())).update();
        if (traceabilityFailure == null) {
            recordTraceability(id, checks, stories);
        }
        tenant.auditOperator(operations.requiredOperatorId(), "m0_scripted_acceptance_recorded",
                "m0_scripted_acceptance", id);
        return new ScriptedRecord(id, failure == null);
    }

    @Transactional
    public UUID openLive(UUID scriptedRunId, UUID restoreDrillId) {
        tenant.select();
        var run = jdbc.sql("""
                SELECT release_digest, traceability_digest FROM m0_scripted_acceptance_runs
                WHERE id = ? AND outcome = 'passed'
                """).param(scriptedRunId).query((rs, ignored) -> new Frozen(
                        rs.getString("release_digest"), rs.getString("traceability_digest")))
                .optional().orElseThrow(() -> new IllegalStateException("Passing scripted evidence is required."));
        if (!restoreReady(run.releaseDigest(), restoreDrillId)) {
            throw new IllegalStateException("Current provider restore evidence is required.");
        }
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO m0_acceptance_runs (
                    id, organisation_id, operator_id, scripted_run_id, release_digest,
                    traceability_digest, restore_drill_id, opened_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).params(id, pilot.organisationId(), operations.requiredOperatorId(), scriptedRunId,
                run.releaseDigest(), run.traceabilityDigest(), restoreDrillId,
                Timestamp.from(clock.instant())).update();
        tenant.auditOperator(operations.requiredOperatorId(), "m0_live_acceptance_opened",
                "m0_acceptance", id);
        return id;
    }

    @Transactional
    public boolean verifyLive(LiveBinding binding) {
        tenant.select();
        requireOpen(binding.runId());
        jdbc.sql("""
                INSERT INTO m0_acceptance_bindings (
                    run_id, organisation_id, discovery_id, interview_mission_id,
                    interview_session_id, findings_package_version_id,
                    investigator_membership_id, participant_id, observer_id,
                    shaping_adaptive, interview_adaptive, no_fabrication,
                    accessibility_passed, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(binding.runId(), pilot.organisationId(), binding.discoveryId(),
                binding.interviewMissionId(), binding.interviewSessionId(),
                binding.findingsPackageVersionId(), binding.investigatorMembershipId(),
                binding.participantId(), binding.observerId(), binding.shapingAdaptive(),
                binding.interviewAdaptive(), binding.noFabrication(), binding.accessibilityPassed(),
                Timestamp.from(clock.instant())).update();

        var journey = journeyComplete(binding);
        var coverage = requiredCoverageComplete(binding);
        var record = retainedRecordComplete(binding);
        var automatic = automaticFailures(binding) == 0;
        var scripted = scriptedStillCurrent(binding.runId());
        var traceability = recordLiveEvidence(binding) && traceabilityComplete(binding.runId());
        var outcomes = List.of(
                journey,
                journey && binding.shapingAdaptive() && binding.noFabrication(),
                journey && coverage && binding.interviewAdaptive(),
                record,
                scripted,
                automatic && binding.noFabrication(),
                binding.accessibilityPassed(),
                traceability);
        var failures = List.of("journey_incomplete", "adaptation_failed", "adaptation_failed",
                "record_incomplete", "scripted_failure", "automatic_failure",
                "accessibility_blocker", "traceability_gap");
        for (var index = 0; index < CRITERIA.size(); index++) {
            recordResult(binding.runId(), CRITERIA.get(index), outcomes.get(index), failures.get(index),
                    index == 1 || index == 2 || index == 5 || index == 6 ? "human" : "system");
        }
        tenant.auditOperator(operations.requiredOperatorId(), "m0_live_acceptance_verified",
                "m0_acceptance", binding.runId());
        return outcomes.stream().allMatch(Boolean::booleanValue);
    }

    @Transactional
    public boolean finalise(UUID runId) {
        tenant.select();
        requireOpen(runId);
        var passed = jdbc.sql("""
                SELECT count(*) = 8 AND bool_and(outcome = 'passed')
                FROM m0_acceptance_results WHERE run_id = ? AND criterion <> 'AC35-FINAL'
                """).param(runId).query(Boolean.class).single();
        recordResult(runId, "AC35-FINAL", passed, "package_not_accepted", "system");
        tenant.auditOperator(operations.requiredOperatorId(), "m0_live_acceptance_finalised",
                "m0_acceptance", runId);
        return passed;
    }

    @Transactional(readOnly = true)
    public String currentMigrationDigest() {
        tenant.select();
        var values = jdbc.sql("""
                SELECT version || ':' || coalesce(checksum::text, 'none')
                FROM flyway_schema_history WHERE success ORDER BY installed_rank
                """).query(String.class).list();
        return sha256(String.join("\n", values));
    }

    private boolean journeyComplete(LiveBinding binding) {
        return exists("""
                SELECT EXISTS (
                    SELECT 1 FROM interview_missions m
                    JOIN interview_sessions s ON s.interview_mission_id = m.id
                    JOIN invitations i ON i.interview_mission_id = m.id AND i.participant_id = s.participant_id
                    JOIN interview_completion_proposals cp ON cp.interview_session_id = s.id
                    JOIN findings_package_versions fpv ON fpv.interview_session_id = s.id
                    JOIN findings_package_decisions fpd ON fpd.findings_package_version_id = fpv.id
                    WHERE m.id = ? AND m.discovery_id = ? AND m.status = 'approved'
                      AND s.id = ? AND s.participant_id = ? AND s.status = 'completed'
                      AND s.started_at IS NOT NULL AND s.commitment_acknowledged_at IS NOT NULL
                      AND i.redeemed_at IS NOT NULL AND i.send_confirmed_at IS NOT NULL
                      AND cp.status = 'confirmed' AND fpv.id = ? AND fpd.decision = 'accepted'
                      AND fpd.actor_membership_id = ? AND length(trim(fpd.notes)) > 0)
                """, binding.interviewMissionId(), binding.discoveryId(), binding.interviewSessionId(),
                binding.participantId(), binding.findingsPackageVersionId(), binding.investigatorMembershipId());
    }

    private boolean requiredCoverageComplete(LiveBinding binding) {
        return exists("""
                SELECT NOT EXISTS (
                    SELECT 1 FROM investigation_items item
                    WHERE item.interview_mission_id = ? AND item.required
                      AND NOT EXISTS (
                        SELECT 1 FROM findings_package_results result
                        WHERE result.findings_package_version_id = ?
                          AND result.investigation_item_id = item.id
                          AND (EXISTS (
                            SELECT 1 FROM knowledge_item_versions version
                            WHERE version.findings_package_version_id = result.findings_package_version_id
                              AND version.investigation_item_id = item.id)
                          OR EXISTS (
                            SELECT 1 FROM findings_package_unresolved_outcomes unresolved
                            WHERE unresolved.findings_package_version_id = result.findings_package_version_id
                              AND unresolved.investigation_item_id = item.id))))
                """, binding.interviewMissionId(), binding.findingsPackageVersionId());
    }

    private boolean retainedRecordComplete(LiveBinding binding) {
        return exists("""
                SELECT EXISTS (SELECT 1 FROM evidence WHERE interview_session_id = ?)
                   AND EXISTS (SELECT 1 FROM findings_package_decisions
                        WHERE findings_package_version_id = ? AND decision = 'accepted')
                   AND NOT EXISTS (
                        SELECT 1 FROM knowledge_items item
                        JOIN knowledge_item_versions version
                          ON version.knowledge_item_id = item.id AND version.version = item.current_version
                        WHERE item.interview_session_id = ?
                          AND NOT EXISTS (SELECT 1 FROM knowledge_item_reviews review
                            WHERE review.knowledge_item_version_id = version.id))
                   AND NOT EXISTS (
                        SELECT 1 FROM findings_package_unresolved_outcomes outcome
                        WHERE outcome.findings_package_version_id = ?
                          AND NOT EXISTS (SELECT 1 FROM findings_unresolved_outcome_reviews review
                            WHERE review.findings_package_version_id = outcome.findings_package_version_id
                              AND review.outcome_id = outcome.outcome_id))
                """, binding.interviewSessionId(), binding.findingsPackageVersionId(),
                binding.interviewSessionId(), binding.findingsPackageVersionId());
    }

    private int automaticFailures(LiveBinding binding) {
        return count("""
                SELECT count(*) FROM (
                    SELECT version.id FROM knowledge_item_versions version
                    JOIN knowledge_items item ON item.id = version.knowledge_item_id
                    WHERE version.findings_package_version_id = ?
                      AND version.version = item.current_version
                      AND NOT EXISTS (SELECT 1 FROM knowledge_item_evidence_citations citation
                        WHERE citation.knowledge_item_version_id = version.id)
                    UNION ALL
                    SELECT citation.knowledge_item_version_id
                    FROM knowledge_item_evidence_citations citation
                    JOIN evidence e ON e.id = citation.evidence_id
                    WHERE citation.knowledge_item_version_id IN (
                        SELECT id FROM knowledge_item_versions WHERE findings_package_version_id = ?)
                      AND (e.interview_session_id <> ? OR e.interview_mission_id <> ?
                        OR (e.source_type IN ('interviewee_answer', 'interviewee_answer_revision')
                          AND e.participant_id <> ?)
                        OR substring(e.answer FROM citation.start_offset + 1
                            FOR citation.end_offset - citation.start_offset) <> citation.quotation)
                    UNION ALL
                    SELECT citation.knowledge_item_version_id
                    FROM knowledge_item_evidence_citations citation
                    JOIN evidence_scope_assessments scope ON scope.evidence_id = citation.evidence_id
                    WHERE citation.knowledge_item_version_id IN (
                        SELECT id FROM knowledge_item_versions WHERE findings_package_version_id = ?)
                      AND scope.assessment = 'out_of_scope'
                    UNION ALL
                    SELECT min(run.id::text)::uuid FROM interview_runtime_runs run
                    WHERE run.interview_session_id = ? AND run.status = 'committed'
                    GROUP BY run.trigger, run.expected_revision
                    HAVING count(*) > 1
                    UNION ALL
                    SELECT run.id FROM interview_runtime_runs run
                    JOIN evidence e ON e.id = run.evidence_id
                    WHERE run.interview_session_id = ? AND e.created_at > run.created_at
                    UNION ALL
                    SELECT question.id FROM interview_questions question
                    WHERE question.interview_session_id = ? AND question.answered_at IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM evidence e WHERE e.question_id = question.id)
                ) failures
                """, binding.findingsPackageVersionId(), binding.findingsPackageVersionId(),
                binding.interviewSessionId(), binding.interviewMissionId(), binding.participantId(),
                binding.findingsPackageVersionId(), binding.interviewSessionId(),
                binding.interviewSessionId(), binding.interviewSessionId());
    }

    private String scriptedFailure(ReleaseManifest release, UUID restoreDrillId,
            Map<String, CheckEvidence> checks, String traceabilityFailure) {
        if (traceabilityFailure != null) return traceabilityFailure;
        if (!restoreReady(release.releaseDigest(), restoreDrillId)) return "restore_unverified";
        return null;
    }

    private String traceabilityFailure(Map<String, CheckEvidence> checks, List<StoryEvidence> stories) {
        if (checks == null || !checks.keySet().equals(SCRIPTED_CHECKS)) return "missing_check";
        if (checks.values().stream().anyMatch(value -> value == null || !"passed".equals(value.outcome())
                || !validEvidenceLocator(value.evidenceLocator()))) return "failed_check";
        if (stories == null || stories.size() != 90) return "missing_check";
        var byId = stories.stream().collect(Collectors.toMap(StoryEvidence::storyId, story -> story,
                (first, duplicate) -> first));
        if (byId.size() != 90 || !byId.keySet().equals(java.util.stream.IntStream.rangeClosed(1, 90)
                .boxed().collect(Collectors.toSet()))) return "missing_check";
        if (!stories.stream().map(StoryEvidence::acceptanceCriterion).collect(Collectors.toSet())
                .containsAll(CRITERIA)) return "missing_check";
        for (var story : stories) {
            if (!CRITERIA.contains(story.acceptanceCriterion())
                    || !(SCRIPTED_CHECKS.contains(story.checkId()) || LIVE_CHECKS.contains(story.checkId()))
                    || !validEvidenceLocator(story.evidenceLocator())) return "missing_check";
            if (SCRIPTED_CHECKS.contains(story.checkId())
                    && !story.evidenceLocator().equals(checks.get(story.checkId()).evidenceLocator())) {
                return "failed_check";
            }
        }
        return null;
    }

    private void recordTraceability(UUID scriptedRunId, Map<String, CheckEvidence> checks,
            List<StoryEvidence> stories) {
        var recordedAt = Timestamp.from(clock.instant());
        checks.forEach((checkId, evidence) -> jdbc.sql("""
                INSERT INTO m0_scripted_check_evidence (
                    organisation_id, scripted_run_id, check_id, outcome, evidence_locator, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """).params(pilot.organisationId(), scriptedRunId, checkId, evidence.outcome(),
                evidence.evidenceLocator(), recordedAt).update());
        stories.forEach(story -> jdbc.sql("""
                INSERT INTO m0_story_traceability (
                    organisation_id, scripted_run_id, story_id, acceptance_criterion,
                    check_id, evidence_locator, evidence_kind, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).params(pilot.organisationId(), scriptedRunId, story.storyId(),
                story.acceptanceCriterion(), story.checkId(), story.evidenceLocator(),
                SCRIPTED_CHECKS.contains(story.checkId()) ? "scripted" : "live", recordedAt).update());
    }

    private boolean recordLiveEvidence(LiveBinding binding) {
        var expectedEvidence = jdbc.sql("""
                SELECT trace.story_id, trace.evidence_locator
                FROM m0_acceptance_runs live
                JOIN m0_story_traceability trace ON trace.scripted_run_id = live.scripted_run_id
                WHERE live.id = ? AND trace.evidence_kind = 'live'
                ORDER BY trace.story_id
                """).param(binding.runId()).query((rs, ignored) -> Map.entry(
                        rs.getInt("story_id"), rs.getString("evidence_locator"))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (binding.liveEvidence() == null || expectedEvidence.isEmpty()
                || !expectedEvidence.equals(binding.liveEvidence())) {
            return false;
        }
        var recordedAt = Timestamp.from(clock.instant());
        binding.liveEvidence().forEach((storyId, locator) -> jdbc.sql("""
                INSERT INTO m0_live_story_evidence (
                    organisation_id, run_id, story_id, evidence_locator, recorded_at
                ) VALUES (?, ?, ?, ?, ?)
                """).params(pilot.organisationId(), binding.runId(), storyId, locator, recordedAt).update());
        return true;
    }

    private boolean traceabilityComplete(UUID runId) {
        return exists("""
                SELECT count(*) = 90 AND bool_and(CASE trace.evidence_kind
                    WHEN 'scripted' THEN EXISTS (
                        SELECT 1 FROM m0_scripted_check_evidence evidence
                        WHERE evidence.scripted_run_id = trace.scripted_run_id
                          AND evidence.check_id = trace.check_id
                          AND evidence.evidence_locator = trace.evidence_locator)
                    WHEN 'live' THEN EXISTS (
                        SELECT 1 FROM m0_live_story_evidence evidence
                        WHERE evidence.run_id = live.id AND evidence.story_id = trace.story_id
                          AND evidence.evidence_locator = trace.evidence_locator)
                    ELSE false END)
                FROM m0_acceptance_runs live
                JOIN m0_story_traceability trace ON trace.scripted_run_id = live.scripted_run_id
                WHERE live.id = ?
                """, runId);
    }

    private boolean scriptedStillCurrent(UUID runId) {
        return exists("""
                SELECT EXISTS (
                    SELECT 1 FROM m0_acceptance_runs live
                    JOIN m0_scripted_acceptance_runs scripted ON scripted.id = live.scripted_run_id
                    JOIN recovery_drill_results recovery ON recovery.id = live.restore_drill_id
                    WHERE live.id = ? AND scripted.outcome = 'passed'
                      AND scripted.release_digest = live.release_digest
                      AND scripted.traceability_digest = live.traceability_digest
                      AND recovery.outcome = 'ready' AND recovery.source_kind = 'provider'
                      AND recovery.application_image_digest LIKE '%@' || live.release_digest
                      AND recovery.schema_version = (SELECT max(version::integer)
                        FROM flyway_schema_history WHERE success)
                      AND recovery.completed_at >= ?)
                """, runId, Timestamp.from(clock.instant().minus(recovery.maximumDrillAge())));
    }

    private boolean restoreReady(String releaseDigest, UUID restoreDrillId) {
        return exists("""
                SELECT EXISTS (SELECT 1 FROM recovery_drill_results
                    WHERE id = ? AND source_kind = 'provider' AND outcome = 'ready'
                      AND application_image_digest LIKE '%@' || ?
                      AND schema_version = (SELECT max(version::integer)
                        FROM flyway_schema_history WHERE success)
                      AND completed_at >= ?)
                """, restoreDrillId, releaseDigest,
                Timestamp.from(clock.instant().minus(recovery.maximumDrillAge())));
    }

    private void requireOpen(UUID runId) {
        if (!exists("SELECT EXISTS (SELECT 1 FROM m0_acceptance_runs WHERE id = ?)", runId)) {
            throw new IllegalArgumentException("M0 acceptance run was not found.");
        }
    }

    private void recordResult(UUID runId, String criterion, boolean passed, String failure, String actor) {
        jdbc.sql("""
                INSERT INTO m0_acceptance_results (
                    id, organisation_id, run_id, criterion, outcome,
                    safe_failure_class, actor_kind, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).params(UUID.randomUUID(), pilot.organisationId(), runId, criterion,
                passed ? "passed" : "failed", passed ? null : failure, actor,
                Timestamp.from(clock.instant())).update();
    }

    private boolean exists(String sql, Object... arguments) {
        return jdbc.sql(sql).params(arguments).query(Boolean.class).single();
    }

    private int count(String sql, Object... arguments) {
        return jdbc.sql(sql).params(arguments).query(Integer.class).single();
    }

    private static void validateReleaseShape(ReleaseManifest release) {
        digest(release.releaseDigest());
        digest(release.migrationDigest());
        digest(release.piRuntimeDigest());
        digest(release.skillDigest());
        digest(release.extensionDigest());
        digest(release.traceabilityDigest());
        if (release.gitCommit() == null || !release.gitCommit().matches("^[a-f0-9]{40}$")) {
            throw new IllegalArgumentException("Git commit is invalid.");
        }
        safe(release.providerAlias());
        safe(release.modelAlias());
    }

    private boolean releaseMatches(ReleaseManifest release) {
        return release.releaseDigest().equals(expected.releaseDigest())
                && release.gitCommit().equals(expected.gitCommit())
                && release.skillDigest().equals(expected.skillDigest())
                && release.extensionDigest().equals(expected.extensionDigest())
                && release.traceabilityDigest().equals(expected.traceabilityDigest())
                && release.migrationDigest().equals(currentMigrationDigest())
                && runtime.image() != null && runtime.image().endsWith("@" + release.piRuntimeDigest())
                && release.providerAlias().equals(runtime.provider())
                && release.modelAlias().equals(runtime.model());
    }

    private static void digest(String value) {
        if (value == null || !value.matches("^sha256:[a-f0-9]{64}$")) {
            throw new IllegalArgumentException("Release digest is invalid.");
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank() || value.length() > 100
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Release identifier is invalid.");
        }
        return value;
    }

    private static boolean validEvidenceLocator(String value) {
        return value != null && !value.isBlank() && value.length() <= 500
                && !value.equalsIgnoreCase("pass") && !value.equalsIgnoreCase("passed")
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Frozen(String releaseDigest, String traceabilityDigest) {}

    public record ReleaseManifest(String releaseDigest, String gitCommit, String migrationDigest,
            String piRuntimeDigest, String skillDigest, String extensionDigest,
            String providerAlias, String modelAlias, String traceabilityDigest) {}

    public record ScriptedRecord(UUID id, boolean passed) {}

    public record StoryEvidence(int storyId, String acceptanceCriterion,
            String checkId, String evidenceLocator) {}

    public record CheckEvidence(String outcome, String evidenceLocator) {}

    public record LiveBinding(UUID runId, UUID discoveryId, UUID interviewMissionId,
            UUID interviewSessionId, UUID findingsPackageVersionId,
            UUID investigatorMembershipId, UUID participantId, UUID observerId,
            boolean shapingAdaptive, boolean interviewAdaptive,
            boolean noFabrication, boolean accessibilityPassed,
            Map<Integer, String> liveEvidence) {}
}
