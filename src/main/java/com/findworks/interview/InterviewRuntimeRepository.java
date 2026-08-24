package com.findworks.interview;

import com.findworks.runtime.InterviewTurnRunner;
import com.findworks.runtime.RuntimeCheckpointCipher;
import com.findworks.runtime.RuntimeFailure;
import com.findworks.runtime.RuntimeProperties;
import com.findworks.security.PilotTenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InterviewRuntimeRepository {

    private static final Set<String> PRIORITIES = Set.of("high", "medium", "low");
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient jdbc;
    private final PilotTenant tenant;
    private final RuntimeCheckpointCipher checkpoints;
    private final RuntimeProperties properties;
    private final Clock clock;

    InterviewRuntimeRepository(JdbcClient jdbc, PilotTenant tenant, RuntimeCheckpointCipher checkpoints,
            RuntimeProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.checkpoints = checkpoints;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Work claimNext() {
        tenant.select();
        exhaustDeadRestarts();
        var row = jdbc.sql("""
                SELECT id, status FROM interview_runtime_runs
                WHERE available_at <= now()
                  AND (status = 'queued'
                    OR (status = 'running' AND lease_until < now() AND process_restarts < 1))
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED LIMIT 1
                """).query((rs, ignored) -> new Claim(
                        rs.getObject("id", UUID.class), rs.getString("status"))).optional();
        if (row.isEmpty()) {
            return null;
        }
        if ("running".equals(row.get().status())) {
            jdbc.sql("""
                    UPDATE interview_runtime_attempts
                    SET outcome = 'process_died', failure_class = 'process_died', finished_at = now()
                    WHERE runtime_run_id = ? AND outcome = 'running'
                    """).param(row.get().id()).update();
        }
        var leaseOwner = UUID.randomUUID();
        var work = jdbc.sql("""
                UPDATE interview_runtime_runs
                SET status = 'running', attempts = attempts + 1,
                    process_restarts = process_restarts + CASE WHEN status = 'running' THEN 1 ELSE 0 END,
                    lease_owner = ?, lease_until = now() + interval '2 minutes',
                    updated_at = now(), error_code = NULL
                WHERE id = ?
                RETURNING id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                          trigger, evidence_id, expected_revision, attempts, model_attempts,
                          process_restarts, lease_owner
                """).params(leaseOwner, row.get().id()).query((rs, ignored) -> new Work(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("discovery_id", UUID.class),
                        rs.getObject("interview_session_id", UUID.class),
                        rs.getObject("interview_mission_id", UUID.class),
                        rs.getString("trigger"), rs.getObject("evidence_id", UUID.class),
                        rs.getInt("expected_revision"), rs.getInt("attempts"),
                        rs.getInt("model_attempts"), rs.getInt("process_restarts"),
                        rs.getObject("lease_owner", UUID.class))).single();
        jdbc.sql("""
                INSERT INTO interview_runtime_attempts (
                    id, organisation_id, runtime_run_id, interview_session_id, execution_attempt, outcome
                ) VALUES (?, ?, ?, ?, ?, 'running')
                """).params(UUID.randomUUID(), work.organisationId(), work.id(), work.sessionId(),
                work.executionAttempt()).update();
        return work;
    }

    @Transactional
    public Prepared prepare(Work work, String runtimeVersion) throws RuntimeFailure {
        var context = context(work);
        var checkpoint = checkpoint(work, runtimeVersion);
        jdbc.sql("""
                UPDATE runtime_credentials SET revoked_at = COALESCE(revoked_at, ?)
                WHERE runtime_run_id = ? AND revoked_at IS NULL AND used_at IS NULL
                """).params(timestamp(clock.instant()), work.id()).update();
        var raw = randomToken();
        var expiresAt = clock.instant().plus(properties.timeout());
        jdbc.sql("""
                INSERT INTO runtime_credentials (
                    id, organisation_id, discovery_id, runtime_run_id, interview_session_id,
                    interview_mission_id, expected_revision, token_hash, operation, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'submit_interview_turn', ?)
                """).params(UUID.randomUUID(), work.organisationId(), work.discoveryId(), work.id(),
                work.sessionId(), work.missionId(), work.expectedRevision(), hash(raw), timestamp(expiresAt)).update();
        return new Prepared(context, checkpoint, raw, expiresAt);
    }

    @Transactional(readOnly = true)
    public Context context(Work work) throws RuntimeFailure {
        tenant.select();
        var mission = jdbc.sql("""
                SELECT m.objective, m.desired_outcome, m.completion_criteria, m.expected_commitment
                FROM interview_runtime_runs r
                JOIN interview_sessions s ON s.id = r.interview_session_id
                    AND s.interview_mission_id = r.interview_mission_id
                    AND s.organisation_id = r.organisation_id
                JOIN interview_missions m ON m.id = r.interview_mission_id
                    AND m.discovery_id = r.discovery_id AND m.organisation_id = r.organisation_id
                JOIN discoveries d ON d.id = r.discovery_id AND d.organisation_id = r.organisation_id
                WHERE r.id = ? AND r.interview_session_id = ? AND r.interview_mission_id = ?
                  AND r.organisation_id = ? AND r.status = 'running' AND r.lease_owner = ?
                  AND s.status = 'active' AND s.revision = r.expected_revision
                  AND s.active_question_id IS NULL AND m.approved_at IS NOT NULL AND d.status = 'active'
                  AND ((r.trigger = 'session_start' AND r.evidence_id IS NULL)
                    OR (r.trigger = 'accepted_evidence' AND EXISTS (
                        SELECT 1 FROM evidence e
                        WHERE e.id = r.evidence_id AND e.interview_session_id = r.interview_session_id
                          AND e.organisation_id = r.organisation_id
                          AND e.source_type = 'interviewee_answer')))
                """).params(work.id(), work.sessionId(), work.missionId(), work.organisationId(), work.leaseOwner())
                .query((rs, ignored) -> new Mission(
                        rs.getString("objective"), rs.getString("desired_outcome"),
                        rs.getString("completion_criteria"), rs.getString("expected_commitment")))
                .optional().orElseThrow(() -> new RuntimeFailure(RuntimeFailure.Kind.STALE_SCOPE, 0));
        var sharedContext = jdbc.sql("""
                SELECT content FROM mission_contexts
                WHERE interview_mission_id = ? AND visibility = 'shared' ORDER BY position
                """).param(work.missionId()).query(String.class).list();
        var boundaries = jdbc.sql("""
                SELECT boundary_kind, content FROM mission_boundaries
                WHERE interview_mission_id = ? ORDER BY position
                """).param(work.missionId()).query((rs, ignored) -> new Boundary(
                        rs.getString("boundary_kind"), rs.getString("content"))).list();
        var terminology = jdbc.sql("""
                SELECT term, meaning FROM mission_terms
                WHERE interview_mission_id = ? ORDER BY position
                """).param(work.missionId()).query((rs, ignored) -> new Term(
                        rs.getString("term"), rs.getString("meaning"))).list();
        var openingGuidance = jdbc.sql("""
                SELECT question FROM mission_opening_questions
                WHERE interview_mission_id = ? ORDER BY position
                """).param(work.missionId()).query(String.class).list();
        var items = jdbc.sql("""
                SELECT i.id, i.position, i.knowledge_gap, i.importance, i.priority,
                       i.relevant_context, i.required, coalesce(r.status, 'unaddressed') result_status
                FROM investigation_items i
                LEFT JOIN investigation_results r ON r.investigation_item_id = i.id
                    AND r.interview_session_id = ?
                WHERE i.interview_mission_id = ? ORDER BY i.position
                """).params(work.sessionId(), work.missionId()).query((rs, ignored) -> {
                    var itemId = rs.getObject("id", UUID.class);
                    var outcomes = jdbc.sql("""
                            SELECT outcome_kind FROM mission_allowed_outcomes
                            WHERE investigation_item_id = ? ORDER BY position
                            """).param(itemId).query(String.class).list();
                    return new Item(itemId, rs.getInt("position"), rs.getString("knowledge_gap"),
                            rs.getString("importance"), rs.getString("priority"),
                            rs.getString("relevant_context"), rs.getBoolean("required"),
                            rs.getString("result_status"), outcomes);
                }).list();
        var conversation = jdbc.sql("""
                SELECT q.id question_id, q.investigation_item_id, q.sequence, q.question, q.human_context,
                       e.id evidence_id, e.answer
                FROM interview_questions q
                LEFT JOIN evidence e ON e.question_id = q.id AND e.source_type = 'interviewee_answer'
                WHERE q.interview_session_id = ? AND q.interview_mission_id = ?
                ORDER BY q.sequence
                """).params(work.sessionId(), work.missionId()).query((rs, ignored) -> new Exchange(
                        rs.getObject("question_id", UUID.class),
                        rs.getObject("investigation_item_id", UUID.class), rs.getInt("sequence"),
                        rs.getString("question"), rs.getString("human_context"),
                        rs.getObject("evidence_id", UUID.class), rs.getString("answer"))).list();
        return new Context(work.id(), work.sessionId(), work.expectedRevision(), work.missionId(),
                work.trigger(), work.evidenceId(), mission.objective(), mission.desiredOutcome(), sharedContext,
                boundaries, terminology, mission.completionCriteria(), mission.expectedCommitment(),
                openingGuidance, items, conversation);
    }

    @Transactional
    public Event complete(Work work, InterviewTurnRunner.Result execution) {
        tenant.select();
        var state = lock(work.id());
        if ("committed".equals(state.status()) || "failed".equals(state.status())) {
            return event(work.id());
        }
        requireLease(work, state);
        requireCredential(work, execution == null ? null : execution.credential());
        if (execution == null || execution.submission() == null
                || execution.runtimeVersion() == null || execution.runtimeVersion().isBlank()
                || execution.modelAttempts() < 1 || execution.modelAttempts() > 3
                || state.modelAttempts() + execution.modelAttempts() > 3) {
            throw new IllegalArgumentException("Runtime returned an invalid Interview turn.");
        }
        var submission = execution.submission();
        validateEnvelope(work, submission);
        var session = jdbc.sql("""
                SELECT status, revision, active_question_id
                FROM interview_sessions
                WHERE id = ? AND interview_mission_id = ? AND organisation_id = ? FOR UPDATE
                """).params(work.sessionId(), work.missionId(), work.organisationId())
                .query((rs, ignored) -> new SessionState(
                        rs.getString("status"), rs.getInt("revision"),
                        rs.getObject("active_question_id", UUID.class))).optional()
                .orElseThrow(() -> new IllegalStateException("Interview Session no longer exists."));
        if (!"active".equals(session.status()) || session.revision() != work.expectedRevision()
                || session.activeQuestionId() != null) {
            throw new IllegalArgumentException("Interview runtime Run is stale.");
        }
        var action = submission.nextAction();
        var targetPriority = jdbc.sql("""
                SELECT priority FROM investigation_items
                WHERE id = ? AND interview_mission_id = ? AND organisation_id = ? AND required
                """).params(action.targetInvestigationItemId(), work.missionId(), work.organisationId())
                .query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Question target is outside the Mission."));
        var highestPriority = jdbc.sql("""
                SELECT priority FROM investigation_items
                WHERE interview_mission_id = ? AND required
                ORDER BY CASE priority WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END, position
                LIMIT 1
                """).param(work.missionId()).query(String.class).single();
        if (!targetPriority.equals(highestPriority) || !PRIORITIES.contains(targetPriority)) {
            throw new IllegalArgumentException("Question target is not in the highest-priority required area.");
        }
        var question = plain(action.question(), 2_000, "question");
        if (jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM interview_questions
                    WHERE interview_session_id = ? AND lower(trim(question)) = lower(trim(?))
                )
                """).params(work.sessionId(), question).query(Boolean.class).single()) {
            throw new IllegalArgumentException("Pi repeated an Interview question.");
        }
        var context = optionalPlain(action.humanContext(), 2_000, "question context");
        var covered = plain(action.progress().covered(), 2_000, "covered progress");
        var current = plain(action.progress().current(), 2_000, "current progress");
        var remaining = plain(action.progress().remaining(), 2_000, "remaining progress");
        var totalRequired = jdbc.sql("""
                SELECT count(*) FROM investigation_items
                WHERE interview_mission_id = ? AND required
                """).param(work.missionId()).query(Integer.class).single();
        var coveredCount = jdbc.sql("""
                SELECT count(*) FROM investigation_results r
                JOIN investigation_items i ON i.id = r.investigation_item_id
                WHERE r.interview_session_id = ? AND i.required
                """).param(work.sessionId()).query(Integer.class).single();
        var questionId = UUID.randomUUID();
        var sequence = jdbc.sql("""
                SELECT coalesce(max(sequence), 0) + 1 FROM interview_questions
                WHERE interview_session_id = ?
                """).param(work.sessionId()).query(Integer.class).single();
        jdbc.sql("""
                INSERT INTO interview_questions (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    investigation_item_id, sequence, question, human_context
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(questionId, work.organisationId(), work.discoveryId(), work.sessionId(),
                work.missionId(), action.targetInvestigationItemId(), sequence, question, context).update();
        var eventId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO interview_application_events (
                    id, organisation_id, discovery_id, interview_session_id, runtime_run_id,
                    question_id, event_type, covered_count, total_required,
                    covered_text, current_text, remaining_text
                ) VALUES (?, ?, ?, ?, ?, ?, 'question_ready', ?, ?, ?, ?, ?)
                """).params(eventId, work.organisationId(), work.discoveryId(), work.sessionId(), work.id(),
                questionId, coveredCount, totalRequired, covered, current, remaining).update();
        var advanced = jdbc.sql("""
                UPDATE interview_sessions
                SET active_question_id = ?, revision = revision + 1
                WHERE id = ? AND revision = ? AND active_question_id IS NULL AND status = 'active'
                """).params(questionId, work.sessionId(), work.expectedRevision()).update();
        if (advanced != 1) {
            throw new IllegalArgumentException("Interview Session changed before the question committed.");
        }
        saveCheckpoint(work, execution.runtimeVersion(), execution.checkpoint());
        var settled = jdbc.sql("""
                UPDATE interview_runtime_runs
                SET status = 'committed', model_attempts = model_attempts + ?, runtime_version = ?,
                    lease_until = NULL, lease_owner = NULL, updated_at = now()
                WHERE id = ? AND lease_owner = ?
                """).params(execution.modelAttempts(), execution.runtimeVersion(), work.id(), work.leaseOwner())
                .update();
        if (settled != 1) {
            throw new IllegalStateException("Interview runtime lease changed before commit.");
        }
        finishAttempt(work, "committed", execution.modelAttempts(), null);
        useCredential(execution.credential());
        tenant.auditSystem("interview_question_ready", "interview_session", work.sessionId());
        return new Event(eventId, "question_ready", questionId, question, context, coveredCount,
                totalRequired, covered, current, remaining);
    }

    @Transactional
    public Event fail(Work work, String runtimeVersion, RuntimeFailure failure) {
        tenant.select();
        var state = lock(work.id());
        if ("committed".equals(state.status()) || "failed".equals(state.status())) {
            return event(work.id());
        }
        requireLease(work, state);
        var reportedModelAttempts = Math.max(0, Math.min(3, failure.modelAttempts()));
        var nextModelAttempts = Math.min(3, state.modelAttempts() + reportedModelAttempts);
        if (runtimeVersion != null && !runtimeVersion.isBlank()) {
            saveCheckpoint(work, runtimeVersion, failure.checkpoint());
        }
        revokeCredentials(work.id());
        var retryProcess = failure.kind() == RuntimeFailure.Kind.PROCESS_DIED
                && state.processRestarts() < 1 && nextModelAttempts < 3;
        var retryModel = failure.kind() == RuntimeFailure.Kind.TRANSIENT_MODEL
                && reportedModelAttempts > 0 && nextModelAttempts < 3;
        if (retryProcess || retryModel) {
            jdbc.sql("""
                    UPDATE interview_runtime_runs
                    SET status = 'queued', model_attempts = ?,
                        process_restarts = process_restarts + CASE WHEN ? THEN 1 ELSE 0 END,
                        available_at = now() + interval '1 second', lease_until = NULL, lease_owner = NULL,
                        updated_at = now(), error_code = ?
                    WHERE id = ? AND lease_owner = ?
                    """).params(nextModelAttempts, retryProcess, failure.kind().code(), work.id(), work.leaseOwner())
                    .update();
            finishAttempt(work, retryProcess ? "process_died" : "transient_model_failure",
                    reportedModelAttempts, failure.kind().code());
            return null;
        }
        finishAttempt(work, "failed", reportedModelAttempts, failure.kind().code());
        return stableFailure(work, runtimeVersion, nextModelAttempts, failure.kind().code());
    }

    @Transactional(readOnly = true)
    public Event replay(UUID runId) {
        tenant.select();
        return event(runId);
    }

    private void exhaustDeadRestarts() {
        var exhausted = jdbc.sql("""
                SELECT id, organisation_id, discovery_id, interview_session_id
                FROM interview_runtime_runs
                WHERE status = 'running' AND lease_until < now() AND process_restarts >= 1
                FOR UPDATE SKIP LOCKED
                """).query((rs, ignored) -> new FailedRun(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("discovery_id", UUID.class),
                        rs.getObject("interview_session_id", UUID.class))).list();
        for (var run : exhausted) {
            jdbc.sql("""
                    UPDATE interview_runtime_attempts
                    SET outcome = 'failed', failure_class = 'process_died', finished_at = now()
                    WHERE runtime_run_id = ? AND outcome = 'running'
                    """).param(run.id()).update();
            jdbc.sql("""
                    INSERT INTO interview_application_events (
                        id, organisation_id, discovery_id, interview_session_id,
                        runtime_run_id, event_type
                    ) VALUES (?, ?, ?, ?, ?, 'runtime_failed')
                    ON CONFLICT (runtime_run_id) DO NOTHING
                    """).params(UUID.randomUUID(), run.organisationId(), run.discoveryId(), run.sessionId(), run.id())
                    .update();
            jdbc.sql("""
                    UPDATE interview_runtime_runs
                    SET status = 'failed', lease_until = NULL, lease_owner = NULL,
                        updated_at = now(), error_code = 'process_died'
                    WHERE id = ?
                    """).param(run.id()).update();
            revokeCredentials(run.id());
            tenant.auditSystem("interview_runtime_failed", "interview_session", run.sessionId());
        }
    }

    private Event stableFailure(Work work, String runtimeVersion, int modelAttempts, String errorCode) {
        jdbc.sql("""
                INSERT INTO interview_application_events (
                    id, organisation_id, discovery_id, interview_session_id, runtime_run_id, event_type
                ) VALUES (?, ?, ?, ?, ?, 'runtime_failed')
                ON CONFLICT (runtime_run_id) DO NOTHING
                """).params(UUID.randomUUID(), work.organisationId(), work.discoveryId(), work.sessionId(), work.id())
                .update();
        jdbc.sql("""
                UPDATE interview_runtime_runs
                SET status = 'failed', model_attempts = ?, runtime_version = ?,
                    lease_until = NULL, lease_owner = NULL, updated_at = now(), error_code = ?
                WHERE id = ? AND lease_owner = ?
                """).params(modelAttempts, runtimeVersion, errorCode, work.id(), work.leaseOwner()).update();
        tenant.auditSystem("interview_runtime_failed", "interview_session", work.sessionId());
        return event(work.id());
    }

    private RunState lock(UUID runId) {
        return jdbc.sql("""
                SELECT status, lease_owner, model_attempts, process_restarts
                FROM interview_runtime_runs WHERE id = ? FOR UPDATE
                """).param(runId).query((rs, ignored) -> new RunState(
                        rs.getString("status"), rs.getObject("lease_owner", UUID.class),
                        rs.getInt("model_attempts"), rs.getInt("process_restarts"))).optional()
                .orElseThrow(() -> new IllegalStateException("Interview runtime Run no longer exists."));
    }

    private void requireLease(Work work, RunState state) {
        if (!"running".equals(state.status()) || !work.leaseOwner().equals(state.leaseOwner())) {
            throw new IllegalStateException("Interview runtime lease is stale.");
        }
    }

    private void requireCredential(Work work, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Runtime credential is invalid.");
        }
        var valid = jdbc.sql("""
                SELECT count(*) FROM runtime_credentials
                WHERE token_hash = ? AND runtime_run_id = ? AND interview_session_id = ?
                  AND interview_mission_id = ? AND expected_revision = ?
                  AND operation = 'submit_interview_turn' AND expires_at > ?
                  AND revoked_at IS NULL AND used_at IS NULL
                """).params(hash(raw), work.id(), work.sessionId(), work.missionId(), work.expectedRevision(),
                timestamp(clock.instant())).query(Integer.class).single();
        if (valid != 1) {
            throw new IllegalArgumentException("Runtime credential scope is invalid.");
        }
    }

    private void useCredential(String raw) {
        jdbc.sql("""
                UPDATE runtime_credentials SET used_at = ?
                WHERE token_hash = ? AND used_at IS NULL AND revoked_at IS NULL
                """).params(timestamp(clock.instant()), hash(raw)).update();
    }

    private void revokeCredentials(UUID runId) {
        jdbc.sql("""
                UPDATE runtime_credentials SET revoked_at = COALESCE(revoked_at, ?)
                WHERE runtime_run_id = ? AND used_at IS NULL
                """).params(timestamp(clock.instant()), runId).update();
    }

    private byte[] checkpoint(Work work, String runtimeVersion) {
        var stored = jdbc.sql("""
                SELECT id, interview_session_id, interview_mission_id, expected_revision,
                       runtime_version, key_id, nonce, ciphertext
                FROM runtime_checkpoints WHERE runtime_run_id = ? AND status = 'usable'
                """).param(work.id()).query((rs, ignored) -> new StoredCheckpoint(
                        rs.getObject("id", UUID.class), rs.getObject("interview_session_id", UUID.class),
                        rs.getObject("interview_mission_id", UUID.class), rs.getInt("expected_revision"),
                        rs.getString("runtime_version"), rs.getString("key_id"),
                        rs.getBytes("nonce"), rs.getBytes("ciphertext"))).optional();
        if (stored.isEmpty()) {
            return null;
        }
        var value = stored.get();
        if (!work.sessionId().equals(value.sessionId()) || !work.missionId().equals(value.missionId())) {
            discardCheckpoint(value.id(), "mission_mismatch");
            return null;
        }
        if (work.expectedRevision() != value.expectedRevision()) {
            discardCheckpoint(value.id(), "revision_mismatch");
            return null;
        }
        if (!runtimeVersion.equals(value.runtimeVersion())) {
            discardCheckpoint(value.id(), "runtime_mismatch");
            return null;
        }
        try {
            return checkpoints.decrypt(value.keyId(), value.nonce(), value.ciphertext(),
                    checkpointAad(work, runtimeVersion));
        } catch (Exception error) {
            discardCheckpoint(value.id(), "decrypt_failed");
            return null;
        }
    }

    private void saveCheckpoint(Work work, String runtimeVersion, byte[] checkpoint) {
        if (checkpoint == null) {
            return;
        }
        var encrypted = checkpoints.encrypt(checkpoint, checkpointAad(work, runtimeVersion));
        jdbc.sql("""
                INSERT INTO runtime_checkpoints (
                    id, organisation_id, discovery_id, runtime_run_id, interview_session_id,
                    interview_mission_id, expected_revision, runtime_version,
                    key_id, nonce, ciphertext
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (runtime_run_id) DO UPDATE
                SET runtime_version = EXCLUDED.runtime_version, key_id = EXCLUDED.key_id,
                    nonce = EXCLUDED.nonce, ciphertext = EXCLUDED.ciphertext,
                    status = 'usable', discard_reason = NULL, discarded_at = NULL, created_at = now()
                """).params(UUID.randomUUID(), work.organisationId(), work.discoveryId(), work.id(), work.sessionId(),
                work.missionId(), work.expectedRevision(), runtimeVersion, encrypted.keyId(),
                encrypted.nonce(), encrypted.ciphertext()).update();
    }

    private void discardCheckpoint(UUID checkpointId, String reason) {
        jdbc.sql("""
                UPDATE runtime_checkpoints
                SET status = 'discarded', discard_reason = ?, discarded_at = now()
                WHERE id = ? AND status = 'usable'
                """).params(reason, checkpointId).update();
    }

    private static String checkpointAad(Work work, String runtimeVersion) {
        return work.organisationId() + ":" + work.sessionId() + ":" + work.missionId() + ":"
                + work.expectedRevision() + ":" + runtimeVersion;
    }

    private void finishAttempt(Work work, String outcome, int modelAttempts, String failureClass) {
        jdbc.sql("""
                UPDATE interview_runtime_attempts
                SET outcome = ?, model_attempts = ?, failure_class = ?, finished_at = now()
                WHERE runtime_run_id = ? AND execution_attempt = ? AND outcome = 'running'
                """).params(outcome, modelAttempts, failureClass, work.id(), work.executionAttempt()).update();
    }

    private Event event(UUID runId) {
        return jdbc.sql("""
                SELECT e.id event_id, e.event_type, q.id question_id, q.question, q.human_context,
                       e.covered_count, e.total_required, e.covered_text, e.current_text, e.remaining_text
                FROM interview_application_events e
                LEFT JOIN interview_questions q ON q.id = e.question_id
                WHERE e.runtime_run_id = ?
                """).param(runId).query((rs, ignored) -> new Event(
                        rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                        rs.getObject("question_id", UUID.class), rs.getString("question"),
                        rs.getString("human_context"), rs.getObject("covered_count", Integer.class),
                        rs.getObject("total_required", Integer.class), rs.getString("covered_text"),
                        rs.getString("current_text"), rs.getString("remaining_text"))).single();
    }

    private static void validateEnvelope(Work work, Submission submission) {
        if (submission == null || !work.id().equals(submission.runId())
                || !work.sessionId().equals(submission.sessionId())
                || work.expectedRevision() != submission.expectedRevision()
                || submission.outcomes() == null || !submission.outcomes().isEmpty()
                || submission.nextAction() == null || !"ask_question".equals(submission.nextAction().kind())
                || submission.nextAction().progress() == null) {
            throw new IllegalArgumentException("Pi returned an invalid Interview turn.");
        }
    }

    private static String plain(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Pi returned invalid " + name + ".");
        }
        return value.trim();
    }

    private static String optionalPlain(String value, int max, String name) {
        return value == null ? null : plain(value, max, name);
    }

    private static String randomToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    public record Work(UUID id, UUID organisationId, UUID discoveryId, UUID sessionId, UUID missionId,
            String trigger, UUID evidenceId, int expectedRevision, int executionAttempt,
            int modelAttempts, int processRestarts, UUID leaseOwner) {}
    public record Prepared(Context context, byte[] checkpoint, String credential, Instant credentialExpiresAt) {
        public Prepared {
            checkpoint = checkpoint == null ? null : checkpoint.clone();
        }

        @Override
        public byte[] checkpoint() {
            return checkpoint == null ? null : checkpoint.clone();
        }
    }
    public record Context(UUID runId, UUID sessionId, int expectedRevision, UUID missionVersionId,
            String trigger, UUID acceptedEvidenceId,
            String objective, String desiredOutcome, List<String> sharedContext, List<Boundary> boundaries,
            List<Term> terminology, String completionCriteria, String expectedCommitment,
            List<String> openingGuidance, List<Item> investigationItems, List<Exchange> conversation) {}
    public record Boundary(String kind, String content) {}
    public record Term(String term, String meaning) {}
    public record Item(UUID id, int position, String knowledgeGap, String importance, String priority,
            String relevantContext, boolean required, String resultStatus, List<String> allowedOutcomes) {}
    public record Exchange(UUID questionId, UUID investigationItemId, int sequence, String question,
            String humanContext, UUID evidenceId, String answer) {}
    public record Submission(UUID runId, UUID sessionId, int expectedRevision,
            List<OutcomeProposal> outcomes, NextAction nextAction) {}
    public record OutcomeProposal(String kind, UUID investigationItemId) {}
    public record NextAction(String kind, UUID targetInvestigationItemId, String question,
            String humanContext, Progress progress) {}
    public record Progress(String covered, String current, String remaining) {}
    public record Event(UUID id, String type, UUID questionId, String question, String humanContext,
            Integer coveredCount, Integer totalRequired, String coveredText, String currentText, String remainingText) {}
    private record Claim(UUID id, String status) {}
    private record Mission(String objective, String desiredOutcome, String completionCriteria,
            String expectedCommitment) {}
    private record SessionState(String status, int revision, UUID activeQuestionId) {}
    private record RunState(String status, UUID leaseOwner, int modelAttempts, int processRestarts) {}
    private record StoredCheckpoint(UUID id, UUID sessionId, UUID missionId, int expectedRevision,
            String runtimeVersion, String keyId, byte[] nonce, byte[] ciphertext) {}
    private record FailedRun(UUID id, UUID organisationId, UUID discoveryId, UUID sessionId) {}
}
