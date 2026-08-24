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
import java.util.HashSet;
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
    private final InterviewCompletionEligibility completion;
    private final RuntimeCheckpointCipher checkpoints;
    private final RuntimeProperties properties;
    private final Clock clock;

    InterviewRuntimeRepository(JdbcClient jdbc, PilotTenant tenant, InterviewCompletionEligibility completion,
            RuntimeCheckpointCipher checkpoints, RuntimeProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.completion = completion;
        this.checkpoints = checkpoints;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Work claimNext() {
        tenant.select();
        exhaustDeadRestarts();
        var row = jdbc.sql("""
                SELECT r.id, r.organisation_id, r.status FROM interview_runtime_runs r
                JOIN interview_sessions s ON s.id = r.interview_session_id
                    AND s.organisation_id = r.organisation_id
                JOIN discoveries d ON d.id = r.discovery_id AND d.organisation_id = r.organisation_id
                WHERE r.available_at <= now() AND d.status = 'active' AND s.access_blocked_at IS NULL
                  AND (r.status = 'queued'
                    OR (r.status = 'running' AND r.lease_until < now() AND r.process_restarts < 1))
                ORDER BY r.created_at
                FOR UPDATE OF r SKIP LOCKED LIMIT 1
                """).query((rs, ignored) -> new Claim(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getString("status"))).optional();
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
        var executionCorrelation = UUID.randomUUID();
        var slot = jdbc.sql("""
                UPDATE pi_worker_slots SET organisation_id = ?, runtime_run_id = ?, shaping_work_id = NULL,
                    lease_owner = ?, lease_expires_at = now() + interval '2 minutes',
                    heartbeat_at = now(), updated_at = now()
                WHERE slot_number = (SELECT slot_number FROM pi_worker_slots
                    WHERE runtime_run_id = ? OR lease_expires_at IS NULL OR lease_expires_at < now()
                    ORDER BY CASE WHEN runtime_run_id = ? THEN 0 ELSE 1 END, slot_number
                    FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING slot_number
                """).params(row.get().organisationId(), row.get().id(), leaseOwner,
                        row.get().id(), row.get().id())
                .query(Integer.class).optional();
        if (slot.isEmpty()) {
            return null;
        }
        var work = jdbc.sql("""
                UPDATE interview_runtime_runs
                SET status = 'running', attempts = attempts + 1,
                    process_restarts = process_restarts + CASE WHEN status = 'running' THEN 1 ELSE 0 END,
                    lease_owner = ?, lease_until = now() + interval '2 minutes', heartbeat_at = now(),
                    execution_correlation_id = ?, updated_at = now(), error_code = NULL
                WHERE id = ?
                RETURNING id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                          work_kind, trigger, evidence_id, source_question_id, expected_revision, generation,
                          attempts, model_attempts,
                          process_restarts, lease_owner, origin_correlation_id, execution_correlation_id
                """).params(leaseOwner, executionCorrelation, row.get().id()).query((rs, ignored) -> new Work(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("discovery_id", UUID.class),
                        rs.getObject("interview_session_id", UUID.class),
                        rs.getObject("interview_mission_id", UUID.class),
                        rs.getString("work_kind"), rs.getString("trigger"), rs.getObject("evidence_id", UUID.class),
                        rs.getObject("source_question_id", UUID.class),
                        rs.getInt("expected_revision"), rs.getInt("generation"), rs.getInt("attempts"),
                        rs.getInt("model_attempts"), rs.getInt("process_restarts"),
                        rs.getObject("lease_owner", UUID.class),
                        rs.getObject("origin_correlation_id", UUID.class),
                        rs.getObject("execution_correlation_id", UUID.class))).single();
        jdbc.sql("""
                INSERT INTO interview_runtime_attempts (
                    id, organisation_id, runtime_run_id, interview_session_id, execution_attempt, outcome
                ) VALUES (?, ?, ?, ?, ?, 'running')
                """).params(UUID.randomUUID(), work.organisationId(), work.id(), work.sessionId(),
                work.executionAttempt()).update();
        return work;
    }

    @Transactional
    public boolean heartbeat(Work work) {
        tenant.select();
        var slot = jdbc.sql("""
                UPDATE pi_worker_slots SET heartbeat_at = now(),
                    lease_expires_at = now() + interval '2 minutes', updated_at = now()
                WHERE runtime_run_id = ? AND lease_owner = ? AND lease_expires_at >= now()
                """).params(work.id(), work.leaseOwner()).update();
        var run = jdbc.sql("""
                UPDATE interview_runtime_runs SET heartbeat_at = now(),
                    lease_until = now() + interval '2 minutes', updated_at = now()
                WHERE id = ? AND status = 'running' AND lease_owner = ? AND lease_until >= now()
                """).params(work.id(), work.leaseOwner()).update();
        if (slot != run) {
            throw new IllegalStateException("Interview runtime worker lease is inconsistent.");
        }
        return run == 1;
    }

    @Transactional
    public Prepared prepare(Work work, String runtimeVersion) throws RuntimeFailure {
        var context = context(work);
        var checkpoint = checkpoint(work, runtimeVersion);
        var credential = issueCredential(work, "submit_interview_turn");
        return new Prepared(context, checkpoint, credential.value(), credential.expiresAt());
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
                  AND s.access_blocked_at IS NULL
                  AND s.active_question_id IS NULL
                  AND m.approved_at IS NOT NULL AND d.status = 'active'
                  AND ((r.trigger IN ('session_start', 'resume') AND r.evidence_id IS NULL)
                    OR (r.trigger = 'accepted_evidence' AND EXISTS (
                        SELECT 1 FROM evidence e
                        WHERE e.id = r.evidence_id AND e.interview_session_id = r.interview_session_id
                          AND e.organisation_id = r.organisation_id
                          AND e.source_type IN ('interviewee_answer', 'interviewee_answer_revision')))
                    OR (r.trigger = 'clarification_request' AND EXISTS (
                        SELECT 1 FROM interview_questions q
                        WHERE q.id = r.source_question_id
                          AND q.interview_session_id = r.interview_session_id
                          AND q.organisation_id = r.organisation_id AND q.answered_at IS NULL)))
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
                SELECT id, boundary_kind, content FROM mission_boundaries
                WHERE interview_mission_id = ? ORDER BY position
                """).param(work.missionId()).query((rs, ignored) -> new Boundary(
                        rs.getObject("id", UUID.class), rs.getString("boundary_kind"),
                        rs.getString("content"))).list();
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
                    var results = jdbc.sql("""
                            SELECT o.kind,
                                   coalesce(u.reason, g.unresolved_subject, c.claim,
                                            f.unresolved_explanation) summary
                            FROM investigation_outcomes o
                            LEFT JOIN unknown_outcomes u ON u.outcome_id = o.id
                            LEFT JOIN ownership_gap_outcomes g ON g.outcome_id = o.id
                            LEFT JOIN candidate_knowledge_claims c ON c.outcome_id = o.id
                            LEFT JOIN conflict_outcomes f ON f.outcome_id = o.id
                            WHERE o.interview_session_id = ? AND o.investigation_item_id = ?
                            ORDER BY o.created_at
                            """).params(work.sessionId(), itemId).query((outcomeRs, ignored2) ->
                                    new ResultOutcome(outcomeRs.getString("kind"),
                                            outcomeRs.getString("summary"))).list();
                    return new Item(itemId, rs.getInt("position"), rs.getString("knowledge_gap"),
                            rs.getString("importance"), rs.getString("priority"),
                            rs.getString("relevant_context"), rs.getBoolean("required"),
                            rs.getString("result_status"), outcomes, results);
                }).list();
        var conversation = jdbc.sql("""
                SELECT q.id question_id, q.investigation_item_id, q.sequence, q.question, q.human_context,
                       q.question_kind, q.clarifies_question_id, q.source_evidence_id,
                       e.id evidence_id, e.answer, e.participation_signal, a.assessment scope_assessment
                FROM interview_questions q
                LEFT JOIN evidence e ON e.question_id = q.id
                    AND e.source_type IN ('interviewee_answer', 'interviewee_answer_revision')
                    AND NOT EXISTS (
                        SELECT 1 FROM evidence revision WHERE revision.revises_evidence_id = e.id
                    )
                LEFT JOIN evidence_scope_assessments a ON a.evidence_id = e.id
                WHERE q.interview_session_id = ? AND q.interview_mission_id = ?
                ORDER BY q.sequence
                """).params(work.sessionId(), work.missionId()).query((rs, ignored) -> new Exchange(
                        rs.getObject("question_id", UUID.class),
                        rs.getObject("investigation_item_id", UUID.class), rs.getInt("sequence"),
                        rs.getString("question"), rs.getString("human_context"),
                        rs.getString("question_kind"), rs.getObject("clarifies_question_id", UUID.class),
                        rs.getObject("source_evidence_id", UUID.class),
                        rs.getObject("evidence_id", UUID.class), rs.getString("answer"),
                        rs.getString("participation_signal"), rs.getString("scope_assessment"))).list();
        return new Context(work.id(), work.sessionId(), work.expectedRevision(), work.missionId(),
                work.trigger(), work.evidenceId(), work.sourceQuestionId(),
                mission.objective(), mission.desiredOutcome(), sharedContext, boundaries, terminology,
                mission.completionCriteria(), mission.expectedCommitment(), openingGuidance, items, conversation);
    }

    @Transactional
    public Event complete(Work work, InterviewTurnRunner.Result execution) {
        tenant.select();
        var state = lock(work.id());
        if ("committed".equals(state.status()) || "failed".equals(state.status())) {
            return event(work.id());
        }
        requireLease(work, state);
        requireCredential(work, execution == null ? null : execution.credential(), "submit_interview_turn");
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
        var outcomes = validateOutcomes(work, submission.outcomes());
        var assessments = validateAssessments(work, submission.scopeAssessments());
        commitOutcomes(work, outcomes);
        commitAssessments(work, assessments);
        var action = submission.nextAction();
        if ("propose_completion".equals(action.kind())) {
            return commitCompletion(work, execution, action);
        }
        var target = jdbc.sql("""
                SELECT i.priority, i.required FROM investigation_items i
                LEFT JOIN investigation_results r ON r.investigation_item_id = i.id
                    AND r.interview_session_id = ?
                WHERE i.id = ? AND i.interview_mission_id = ? AND i.organisation_id = ?
                  AND coalesce(r.status, 'unaddressed') <> 'explicit_outcome'
                """).params(work.sessionId(), action.targetInvestigationItemId(),
                work.missionId(), work.organisationId())
                .query((rs, ignored) -> new Target(rs.getString("priority"), rs.getBoolean("required"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("Question target is outside the Mission."));
        var frontier = jdbc.sql("""
                SELECT i.priority, i.required FROM investigation_items i
                LEFT JOIN investigation_results r ON r.investigation_item_id = i.id
                    AND r.interview_session_id = ?
                WHERE i.interview_mission_id = ?
                  AND coalesce(r.status, 'unaddressed') <> 'explicit_outcome'
                ORDER BY i.required DESC,
                    CASE i.priority WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END, i.position
                LIMIT 1
                """).params(work.sessionId(), work.missionId())
                .query((rs, ignored) -> new Target(rs.getString("priority"), rs.getBoolean("required"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("No uncovered Investigation area remains."));
        if (!target.equals(frontier) || !PRIORITIES.contains(target.priority())) {
            throw new IllegalArgumentException("Question target is not in the current Investigation frontier.");
        }
        var questionPlan = validateQuestion(work, action);
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
                    investigation_item_id, sequence, question, human_context, question_kind,
                    clarifies_question_id, source_evidence_id, paraphrase_reason
                )
                SELECT ?, r.organisation_id, r.discovery_id, r.interview_session_id,
                       r.interview_mission_id, ?, ?, ?, ?, ?, ?, ?, ?
                FROM interview_runtime_runs r WHERE r.id = ?
                """).params(questionId, action.targetInvestigationItemId(), sequence, question, context,
                questionPlan.kind(), questionPlan.clarifiesQuestionId(), questionPlan.sourceEvidenceId(),
                questionPlan.paraphraseReason(), work.id()).update();
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
        settle(work, execution);
        tenant.auditSystem("interview_question_ready", "interview_session", work.sessionId());
        return new Event(eventId, "question_ready", questionId, question, context, coveredCount,
                totalRequired, covered, current, remaining, null, null);
    }

    private Event commitCompletion(Work work, InterviewTurnRunner.Result execution, NextAction action) {
        completion.requireEligible(work.sessionId(), work.missionId(), work.organisationId());
        var recap = plain(action.completionRecap(), 2_000, "completion recap");
        var lowerRecap = recap.toLowerCase(java.util.Locale.ROOT);
        if (lowerRecap.contains("all resolved") || lowerRecap.contains("no unresolved")) {
            throw new IllegalArgumentException("Completion recap cannot describe unresolved points as resolved.");
        }
        var requested = action.unresolvedReferences();
        if (requested == null || requested.size() > 50 || requested.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Completion proposal has invalid unresolved references.");
        }
        var unresolved = completion.unresolved(work.sessionId(), work.missionId(), work.organisationId());
        var expected = unresolved.stream().map(value ->
                new UnresolvedReference(value.investigationItemId(), value.kind())).toList();
        if (!requested.equals(expected)) {
            throw new IllegalArgumentException("Completion proposal does not reference every unresolved outcome.");
        }
        if (action.targetInvestigationItemId() != null || action.question() != null
                || action.humanContext() != null || action.sourceQuestionId() != null
                || action.sourceEvidenceId() != null || action.paraphraseReason() != null
                || action.progress() != null) {
            throw new IllegalArgumentException("Completion proposal cannot also ask a Question.");
        }
        var proposalId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO interview_completion_proposals (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    runtime_run_id, proposed_revision, recap
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """).params(proposalId, work.organisationId(), work.discoveryId(), work.sessionId(),
                work.missionId(), work.id(), work.expectedRevision(), recap).update();
        for (int position = 0; position < unresolved.size(); position++) {
            var reference = unresolved.get(position);
            jdbc.sql("""
                    INSERT INTO interview_completion_unresolved_refs (
                        organisation_id, interview_session_id, interview_mission_id,
                        investigation_item_id, completion_proposal_id, outcome_id, position
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """).params(work.organisationId(), work.sessionId(), work.missionId(),
                    reference.investigationItemId(), proposalId, reference.outcomeId(), position).update();
        }
        var eventId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO interview_application_events (
                    id, organisation_id, discovery_id, interview_session_id,
                    runtime_run_id, completion_proposal_id, event_type
                ) VALUES (?, ?, ?, ?, ?, ?, 'completion_confirmation_ready')
                """).params(eventId, work.organisationId(), work.discoveryId(), work.sessionId(),
                work.id(), proposalId).update();
        var advanced = jdbc.sql("""
                UPDATE interview_sessions
                SET current_completion_proposal_id = ?, revision = revision + 1
                WHERE id = ? AND revision = ? AND active_question_id IS NULL
                  AND current_completion_proposal_id IS NULL AND status = 'active'
                """).params(proposalId, work.sessionId(), work.expectedRevision()).update();
        if (advanced != 1) {
            throw new IllegalArgumentException("Interview Session changed before completion was proposed.");
        }
        settle(work, execution);
        tenant.auditSystem("interview_completion_proposed", "interview_session", work.sessionId());
        return new Event(eventId, "completion_confirmation_ready", null, null, null,
                null, null, null, null, null, proposalId, recap);
    }

    private void settle(Work work, InterviewTurnRunner.Result execution) {
        saveCheckpoint(work, execution.runtimeVersion(), execution.checkpoint());
        settleExternal(work, execution.runtimeVersion(), execution.modelAttempts(), execution.credential());
    }

    Credential issueCredential(Work work, String operation) {
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
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(UUID.randomUUID(), work.organisationId(), work.discoveryId(), work.id(),
                work.sessionId(), work.missionId(), work.expectedRevision(), hash(raw), operation,
                timestamp(expiresAt)).update();
        return new Credential(raw, expiresAt);
    }

    void requireExternalCommit(Work work, String credential, String operation) {
        var state = lock(work.id());
        if ("committed".equals(state.status())) {
            return;
        }
        requireLease(work, state);
        requireCredential(work, credential, operation);
    }

    void settleExternal(Work work, String runtimeVersion, int modelAttempts, String credential) {
        var settled = jdbc.sql("""
                UPDATE interview_runtime_runs
                SET status = 'committed', model_attempts = model_attempts + ?, runtime_version = ?,
                    lease_until = NULL, lease_owner = NULL, heartbeat_at = NULL, updated_at = now()
                WHERE id = ? AND lease_owner = ?
                """).params(modelAttempts, runtimeVersion, work.id(), work.leaseOwner())
                .update();
        if (settled != 1) {
            throw new IllegalStateException("Interview runtime lease changed before commit.");
        }
        finishAttempt(work, "committed", modelAttempts, null);
        useCredential(credential);
        releaseSlot(work.id(), work.leaseOwner());
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
                        heartbeat_at = NULL,
                        updated_at = now(), error_code = ?
                    WHERE id = ? AND lease_owner = ?
                    """).params(nextModelAttempts, retryProcess, failure.kind().code(), work.id(), work.leaseOwner())
                    .update();
            finishAttempt(work, retryProcess ? "process_died" : "transient_model_failure",
                    reportedModelAttempts, failure.kind().code());
            releaseSlot(work.id(), work.leaseOwner());
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
                SELECT id, organisation_id, discovery_id, interview_session_id, work_kind
                FROM interview_runtime_runs
                WHERE status = 'running' AND lease_until < now() AND process_restarts >= 1
                FOR UPDATE SKIP LOCKED
                """).query((rs, ignored) -> new FailedRun(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("discovery_id", UUID.class),
                        rs.getObject("interview_session_id", UUID.class), rs.getString("work_kind"))).list();
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
                    SET status = 'failed', lease_until = NULL, lease_owner = NULL, heartbeat_at = NULL,
                        updated_at = now(), error_code = 'process_died'
                    WHERE id = ?
                    """).param(run.id()).update();
            revokeCredentials(run.id());
            releaseSlot(run.id(), null);
            tenant.auditSystem("findings_extraction".equals(run.workKind())
                    ? "findings_extraction_failed" : "interview_runtime_failed",
                    "interview_session", run.sessionId());
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
                    lease_until = NULL, lease_owner = NULL, heartbeat_at = NULL,
                    updated_at = now(), error_code = ?
                WHERE id = ? AND lease_owner = ?
                """).params(modelAttempts, runtimeVersion, errorCode, work.id(), work.leaseOwner()).update();
        releaseSlot(work.id(), work.leaseOwner());
        tenant.auditSystem("findings_extraction".equals(work.workKind())
                ? "findings_extraction_failed" : "interview_runtime_failed",
                "interview_session", work.sessionId());
        return event(work.id());
    }

    private void releaseSlot(UUID runId, UUID leaseOwner) {
        var sql = """
                UPDATE pi_worker_slots SET organisation_id = NULL, runtime_run_id = NULL,
                    shaping_work_id = NULL, lease_owner = NULL, lease_expires_at = NULL,
                    heartbeat_at = NULL, updated_at = now()
                WHERE runtime_run_id = ?
                """ + (leaseOwner == null ? " AND lease_expires_at < now()" : " AND lease_owner = ?");
        var operation = jdbc.sql(sql).param(runId);
        if (leaseOwner != null) {
            operation.param(leaseOwner);
        }
        operation.update();
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

    private void requireCredential(Work work, String raw, String operation) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Runtime credential is invalid.");
        }
        var valid = jdbc.sql("""
                SELECT count(*) FROM runtime_credentials
                WHERE token_hash = ? AND runtime_run_id = ? AND interview_session_id = ?
                  AND interview_mission_id = ? AND expected_revision = ?
                  AND operation = ? AND expires_at > ?
                  AND revoked_at IS NULL AND used_at IS NULL
                """).params(hash(raw), work.id(), work.sessionId(), work.missionId(), work.expectedRevision(),
                operation, timestamp(clock.instant())).query(Integer.class).single();
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
                       e.covered_count, e.total_required, e.covered_text, e.current_text, e.remaining_text,
                       p.id completion_proposal_id, p.recap completion_recap
                FROM interview_application_events e
                LEFT JOIN interview_questions q ON q.id = e.question_id
                LEFT JOIN interview_completion_proposals p ON p.id = e.completion_proposal_id
                WHERE e.runtime_run_id = ?
                """).param(runId).query((rs, ignored) -> new Event(
                        rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                        rs.getObject("question_id", UUID.class), rs.getString("question"),
                        rs.getString("human_context"), rs.getObject("covered_count", Integer.class),
                        rs.getObject("total_required", Integer.class), rs.getString("covered_text"),
                        rs.getString("current_text"), rs.getString("remaining_text"),
                        rs.getObject("completion_proposal_id", UUID.class),
                        rs.getString("completion_recap"))).single();
    }

    private List<PreparedOutcome> validateOutcomes(Work work, List<OutcomeProposal> proposals) {
        if (proposals.size() > 10) {
            throw new IllegalArgumentException("Pi returned too many Investigation outcomes.");
        }
        return proposals.stream().map(proposal -> {
            if (proposal == null || !Set.of("supported_knowledge", "assumption", "unknown", "conflict",
                    "ownership_gap").contains(proposal.kind()) || proposal.investigationItemId() == null) {
                throw new IllegalArgumentException("Pi returned an invalid Investigation outcome.");
            }
            var result = jdbc.sql("""
                    SELECT r.id, r.status FROM investigation_results r
                    JOIN investigation_items i ON i.id = r.investigation_item_id
                        AND i.interview_mission_id = r.interview_mission_id
                        AND i.organisation_id = r.organisation_id
                    WHERE r.interview_session_id = ? AND r.interview_mission_id = ?
                      AND r.organisation_id = ? AND r.investigation_item_id = ?
                    """).params(work.sessionId(), work.missionId(), work.organisationId(),
                    proposal.investigationItemId()).query((rs, ignored) -> new ResultState(
                            rs.getObject("id", UUID.class), rs.getString("status"))).optional()
                    .orElseThrow(() -> new IllegalArgumentException("Outcome target has no Investigation Result."));
            if ("explicit_outcome".equals(result.status())) {
                throw new IllegalArgumentException("Investigation Result already has an explicit outcome.");
            }
            var allowedKind = switch (proposal.kind()) {
                case "assumption" -> "supported_knowledge";
                default -> proposal.kind();
            };
            var allowed = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM mission_allowed_outcomes
                        WHERE investigation_item_id = ? AND interview_mission_id = ?
                          AND organisation_id = ? AND outcome_kind = ?)
                    """).params(proposal.investigationItemId(), work.missionId(),
                    work.organisationId(), allowedKind).query(Boolean.class).single();
            if (!allowed) {
                throw new IllegalArgumentException("Outcome kind is not allowed by the Mission.");
            }
            var members = proposal.conflictMembers() == null ? List.<ConflictMemberProposal>of()
                    : proposal.conflictMembers();
            if (members.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("Conflict contains an invalid member.");
            }
            var evidenceIds = "conflict".equals(proposal.kind())
                    ? members.stream().map(ConflictMemberProposal::evidenceId).toList()
                    : proposal.evidenceIds();
            if (evidenceIds == null || evidenceIds.isEmpty() || evidenceIds.size() > 10
                    || new HashSet<>(evidenceIds).size() != evidenceIds.size()) {
                throw new IllegalArgumentException("Outcome requires distinct linked Evidence.");
            }
            var evidence = evidenceIds.stream().map(id -> evidence(work, proposal.investigationItemId(), id)).toList();
            validateParticipationSignals(proposal, evidence);
            var id = UUID.randomUUID();
            return switch (proposal.kind()) {
                case "unknown" -> {
                    if (!Set.of("did_not_know", "declined", "evidence_insufficient", "owner_unidentified")
                            .contains(proposal.reason())) {
                        throw new IllegalArgumentException("Unknown requires a valid reason.");
                    }
                    yield new PreparedOutcome(id, result.id(), proposal, evidenceIds,
                            optionalPlain(proposal.explanation(), 2_000, "Unknown explanation"), List.of(), true);
                }
                case "ownership_gap" -> {
                    var subject = plain(proposal.unresolvedSubject(), 2_000, "unresolved subject");
                    var why = plain(proposal.whyCurrentParticipantCannotAnswer(), 2_000, "ownership reason");
                    var ownerName = optionalPlain(proposal.ownerName(), 300, "owner name");
                    var ownerDescription = optionalPlain(proposal.ownerDescription(), 1_000, "owner description");
                    if (ownerName == null && ownerDescription == null) {
                        throw new IllegalArgumentException("Ownership Gap requires an owner name or description.");
                    }
                    yield new PreparedOutcome(id, result.id(), proposal.withOwnership(
                            subject, why, ownerName, ownerDescription), evidenceIds, null, List.of(), true);
                }
                case "supported_knowledge", "assumption" -> {
                    var claim = plain(proposal.claim(), 4_000, "candidate claim");
                    var knowledgeKind = "assumption".equals(proposal.kind()) ? "assumption" : proposal.knowledgeKind();
                    if ("supported_knowledge".equals(proposal.kind())
                            && !Set.of("fact", "rule", "decision", "term", "exception").contains(knowledgeKind)) {
                        throw new IllegalArgumentException("Candidate claim requires a valid kind.");
                    }
                    yield new PreparedOutcome(id, result.id(), proposal.withClaim(claim, knowledgeKind),
                            evidenceIds, null, List.of(), "supported_knowledge".equals(proposal.kind()));
                }
                case "conflict" -> {
                    if (members.size() < 2 || members.size() > 10) {
                        throw new IllegalArgumentException("Conflict requires at least two incompatible members.");
                    }
                    var preparedMembers = members.stream().map(member -> new ConflictMemberProposal(
                            plain(member.claim(), 4_000, "Conflict claim"), member.evidenceId())).toList();
                    yield new PreparedOutcome(id, result.id(), proposal, evidenceIds,
                            plain(proposal.explanation(), 4_000, "Conflict explanation"), preparedMembers, true);
                }
                default -> throw new IllegalArgumentException("Pi returned an invalid Investigation outcome.");
            };
        }).toList();
    }

    private EvidenceState evidence(Work work, UUID itemId, UUID evidenceId) {
        if (evidenceId == null) {
            throw new IllegalArgumentException("Outcome Evidence is missing.");
        }
        return jdbc.sql("""
                SELECT e.id, e.participation_signal, e.answer FROM evidence e
                JOIN interview_sessions s ON s.id = e.interview_session_id
                    AND s.participant_id = e.participant_id AND s.organisation_id = e.organisation_id
                WHERE e.id = ? AND e.interview_session_id = ? AND e.interview_mission_id = ?
                  AND e.organisation_id = ? AND e.investigation_item_id = ?
                  AND e.source_type IN ('interviewee_answer', 'interviewee_answer_revision')
                """).params(evidenceId, work.sessionId(), work.missionId(), work.organisationId(), itemId)
                .query((rs, ignored) -> new EvidenceState(rs.getObject("id", UUID.class),
                        rs.getString("participation_signal"), rs.getString("answer"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("Outcome Evidence is outside the Interview scope."));
    }

    private static void validateParticipationSignals(OutcomeProposal proposal, List<EvidenceState> evidence) {
        for (var item : evidence) {
            if (("did_not_know".equals(item.participationSignal())
                    && !("unknown".equals(proposal.kind()) && "did_not_know".equals(proposal.reason()))
                    || "declined".equals(item.participationSignal())
                    && !("unknown".equals(proposal.kind()) && "declined".equals(proposal.reason()))
                    || "other_owner".equals(item.participationSignal())
                    && !"ownership_gap".equals(proposal.kind()))) {
                throw new IllegalArgumentException("Outcome contradicts the interviewee's explicit choice.");
            }
            if ("other_owner".equals(item.participationSignal())) {
                var owner = item.answer().substring(item.answer().indexOf(':') + 1).trim();
                if (!owner.equals(proposal.ownerName()) && !owner.equals(proposal.ownerDescription())) {
                    throw new IllegalArgumentException("Ownership Gap changed the interviewee's owner detail.");
                }
            }
        }
    }

    private List<PreparedAssessment> validateAssessments(Work work, List<ScopeAssessmentProposal> proposals) {
        if (proposals.size() > 10) {
            throw new IllegalArgumentException("Pi returned too many scope assessments.");
        }
        var seen = new HashSet<UUID>();
        return proposals.stream().map(proposal -> {
            if (proposal == null || proposal.evidenceId() == null || !seen.add(proposal.evidenceId())
                    || !Set.of("in_scope", "out_of_scope").contains(proposal.assessment())) {
                throw new IllegalArgumentException("Pi returned an invalid Evidence scope assessment.");
            }
            var evidence = jdbc.sql("""
                    SELECT investigation_item_id FROM evidence
                    WHERE id = ? AND interview_session_id = ? AND interview_mission_id = ?
                      AND organisation_id = ?
                      AND source_type IN ('interviewee_answer', 'interviewee_answer_revision')
                    """).params(proposal.evidenceId(), work.sessionId(), work.missionId(), work.organisationId())
                    .query(UUID.class).optional()
                    .orElseThrow(() -> new IllegalArgumentException("Scope Evidence is outside the Interview."));
            if ("out_of_scope".equals(proposal.assessment())) {
                var boundary = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM mission_boundaries
                            WHERE id = ? AND interview_mission_id = ? AND organisation_id = ?)
                        """).params(proposal.missionBoundaryId(), work.missionId(), work.organisationId())
                        .query(Boolean.class).single();
                if (!boundary) {
                    throw new IllegalArgumentException("Out-of-scope Evidence requires a Mission boundary.");
                }
            } else if (proposal.missionBoundaryId() != null) {
                throw new IllegalArgumentException("In-scope Evidence cannot cite an excluding boundary.");
            }
            return new PreparedAssessment(UUID.randomUUID(), evidence, proposal.evidenceId(),
                    proposal.assessment(), proposal.missionBoundaryId(),
                    plain(proposal.rationale(), 2_000, "scope rationale"));
        }).toList();
    }

    private void commitOutcomes(Work work, List<PreparedOutcome> outcomes) {
        for (var outcome : outcomes) {
            var proposal = outcome.proposal();
            jdbc.sql("""
                    INSERT INTO investigation_outcomes (
                        id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                        investigation_item_id, investigation_result_id, runtime_run_id, kind
                    ) SELECT ?, r.organisation_id, r.discovery_id, r.interview_session_id,
                             r.interview_mission_id, ?, ?, r.id, ?
                      FROM interview_runtime_runs r WHERE r.id = ?
                    """).params(outcome.id(), proposal.investigationItemId(), outcome.resultId(),
                    proposal.kind(), work.id()).update();
            for (var evidenceId : outcome.evidenceIds()) {
                jdbc.sql("""
                        INSERT INTO investigation_outcome_evidence (
                            organisation_id, interview_session_id, interview_mission_id,
                            investigation_item_id, outcome_id, evidence_id
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """).params(work.organisationId(), work.sessionId(), work.missionId(),
                        proposal.investigationItemId(), outcome.id(), evidenceId).update();
            }
            switch (proposal.kind()) {
                case "unknown" -> jdbc.sql("""
                        INSERT INTO unknown_outcomes (outcome_id, organisation_id, reason, explanation)
                        VALUES (?, ?, ?, ?)
                        """).params(outcome.id(), work.organisationId(), proposal.reason(), outcome.explanation()).update();
                case "ownership_gap" -> jdbc.sql("""
                        INSERT INTO ownership_gap_outcomes (
                            outcome_id, organisation_id, unresolved_subject,
                            why_current_participant_cannot_answer, owner_name, owner_description
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """).params(outcome.id(), work.organisationId(), proposal.unresolvedSubject(),
                        proposal.whyCurrentParticipantCannotAnswer(), proposal.ownerName(),
                        proposal.ownerDescription()).update();
                case "supported_knowledge", "assumption" -> jdbc.sql("""
                        INSERT INTO candidate_knowledge_claims (
                            outcome_id, organisation_id, knowledge_kind, claim, confirmation_state
                        ) VALUES (?, ?, ?, ?, ?)
                        """).params(outcome.id(), work.organisationId(), proposal.knowledgeKind(),
                        proposal.claim(), "assumption".equals(proposal.kind()) ? "unconfirmed" : "confirmed").update();
                case "conflict" -> {
                    jdbc.sql("""
                            INSERT INTO conflict_outcomes (outcome_id, organisation_id, unresolved_explanation)
                            VALUES (?, ?, ?)
                            """).params(outcome.id(), work.organisationId(), outcome.explanation()).update();
                    for (int position = 0; position < outcome.members().size(); position++) {
                        var member = outcome.members().get(position);
                        jdbc.sql("""
                                INSERT INTO conflict_members (
                                    organisation_id, interview_session_id, interview_mission_id,
                                    investigation_item_id, conflict_outcome_id, position, claim, evidence_id
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """).params(work.organisationId(), work.sessionId(), work.missionId(),
                                proposal.investigationItemId(), outcome.id(), position,
                                member.claim(), member.evidenceId()).update();
                    }
                }
                default -> throw new IllegalArgumentException("Pi returned an invalid Investigation outcome.");
            }
            if (outcome.terminal()) {
                jdbc.sql("""
                        UPDATE investigation_results SET status = 'explicit_outcome'
                        WHERE id = ? AND interview_session_id = ? AND status = 'exploring'
                        """).params(outcome.resultId(), work.sessionId()).update();
            }
        }
    }

    private void commitAssessments(Work work, List<PreparedAssessment> assessments) {
        for (var assessment : assessments) {
            jdbc.sql("""
                    INSERT INTO evidence_scope_assessments (
                        id, organisation_id, interview_session_id, interview_mission_id,
                        investigation_item_id, evidence_id, runtime_run_id,
                        assessment, mission_boundary_id, rationale
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(assessment.id(), work.organisationId(), work.sessionId(), work.missionId(),
                    assessment.itemId(), assessment.evidenceId(), work.id(), assessment.assessment(),
                    assessment.boundaryId(), assessment.rationale()).update();
        }
    }

    private QuestionPlan validateQuestion(Work work, NextAction action) {
        if (action.completionRecap() != null
                || action.unresolvedReferences() != null && !action.unresolvedReferences().isEmpty()) {
            throw new IllegalArgumentException("Question action cannot carry a completion proposal.");
        }
        return switch (action.kind()) {
            case "ask_question" -> {
                if (action.sourceQuestionId() != null || action.sourceEvidenceId() != null
                        || action.paraphraseReason() != null) {
                    throw new IllegalArgumentException("Ordinary Question cannot carry clarification links.");
                }
                yield new QuestionPlan("ordinary", null, null, null);
            }
            case "ask_clarification" -> {
                if (!"clarification_request".equals(work.trigger())
                        || !java.util.Objects.equals(work.sourceQuestionId(), action.sourceQuestionId())
                        || action.sourceEvidenceId() != null || action.paraphraseReason() != null) {
                    throw new IllegalArgumentException("Clarification Question has invalid lineage.");
                }
                var itemId = jdbc.sql("""
                        SELECT investigation_item_id FROM interview_questions
                        WHERE id = ? AND interview_session_id = ? AND organisation_id = ?
                          AND answered_at IS NULL
                        """).params(action.sourceQuestionId(), work.sessionId(), work.organisationId())
                        .query(UUID.class).optional()
                        .orElseThrow(() -> new IllegalArgumentException("Clarified Question is outside the Interview."));
                if (!itemId.equals(action.targetInvestigationItemId())) {
                    throw new IllegalArgumentException("Clarification must stay with its Investigation Item.");
                }
                yield new QuestionPlan("clarification", action.sourceQuestionId(), null, null);
            }
            case "ask_paraphrase_confirmation" -> {
                if (action.sourceQuestionId() != null || action.sourceEvidenceId() == null
                        || !Set.of("ambiguity", "contradiction", "inference", "material_importance")
                                .contains(action.paraphraseReason())) {
                    throw new IllegalArgumentException("Paraphrase confirmation has invalid lineage.");
                }
                evidence(work, action.targetInvestigationItemId(), action.sourceEvidenceId());
                var outOfScope = jdbc.sql("""
                        SELECT EXISTS (SELECT 1 FROM evidence_scope_assessments
                            WHERE evidence_id = ? AND assessment = 'out_of_scope')
                        """).param(action.sourceEvidenceId()).query(Boolean.class).single();
                if (outOfScope) {
                    throw new IllegalArgumentException("Out-of-scope Evidence cannot drive follow-up.");
                }
                yield new QuestionPlan("paraphrase_confirmation", null,
                        action.sourceEvidenceId(), action.paraphraseReason());
            }
            default -> throw new IllegalArgumentException("Pi returned an invalid Interview action.");
        };
    }

    private static void validateEnvelope(Work work, Submission submission) {
        if (submission == null || !work.id().equals(submission.runId())
                || !work.sessionId().equals(submission.sessionId())
                || work.expectedRevision() != submission.expectedRevision()
                || submission.outcomes() == null || submission.scopeAssessments() == null
                || submission.nextAction() == null
                || !Set.of("ask_question", "ask_clarification", "ask_paraphrase_confirmation", "propose_completion")
                        .contains(submission.nextAction().kind())
                || (!"propose_completion".equals(submission.nextAction().kind())
                    && submission.nextAction().progress() == null)) {
            throw new IllegalArgumentException("Pi returned an invalid Interview turn.");
        }
        if ("session_start".equals(work.trigger())
                && (!submission.outcomes().isEmpty() || !submission.scopeAssessments().isEmpty()
                    || !"ask_question".equals(submission.nextAction().kind()))
                || "resume".equals(work.trigger())
                && (!submission.outcomes().isEmpty() || !submission.scopeAssessments().isEmpty()
                    || !Set.of("ask_question", "propose_completion").contains(submission.nextAction().kind()))
                || "clarification_request".equals(work.trigger())
                && (!submission.outcomes().isEmpty() || !submission.scopeAssessments().isEmpty()
                    || !"ask_clarification".equals(submission.nextAction().kind()))) {
            throw new IllegalArgumentException("Pi returned effects that do not match the Interview trigger.");
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
            String workKind, String trigger, UUID evidenceId, UUID sourceQuestionId,
            int expectedRevision, int generation, int executionAttempt,
            int modelAttempts, int processRestarts, UUID leaseOwner,
            UUID originCorrelationId, UUID executionCorrelationId) {}
    record Credential(String value, Instant expiresAt) {}
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
            String trigger, UUID acceptedEvidenceId, UUID sourceQuestionId,
            String objective, String desiredOutcome, List<String> sharedContext, List<Boundary> boundaries,
            List<Term> terminology, String completionCriteria, String expectedCommitment,
            List<String> openingGuidance, List<Item> investigationItems, List<Exchange> conversation) {}
    public record Boundary(UUID id, String kind, String content) {}
    public record Term(String term, String meaning) {}
    public record Item(UUID id, int position, String knowledgeGap, String importance, String priority,
            String relevantContext, boolean required, String resultStatus, List<String> allowedOutcomes,
            List<ResultOutcome> outcomes) {}
    public record ResultOutcome(String kind, String summary) {}
    public record Exchange(UUID questionId, UUID investigationItemId, int sequence, String question,
            String humanContext, String questionKind, UUID clarifiesQuestionId, UUID sourceEvidenceId,
            UUID evidenceId, String answer, String participationSignal, String scopeAssessment) {}
    public record Submission(UUID runId, UUID sessionId, int expectedRevision,
            List<OutcomeProposal> outcomes, List<ScopeAssessmentProposal> scopeAssessments,
            NextAction nextAction) {
        public Submission(UUID runId, UUID sessionId, int expectedRevision,
                List<OutcomeProposal> outcomes, NextAction nextAction) {
            this(runId, sessionId, expectedRevision, outcomes, List.of(), nextAction);
        }
    }
    public record OutcomeProposal(String kind, UUID investigationItemId, List<UUID> evidenceIds,
            String reason, String explanation, String knowledgeKind, String claim,
            String unresolvedSubject, String whyCurrentParticipantCannotAnswer,
            String ownerName, String ownerDescription, List<ConflictMemberProposal> conflictMembers) {
        public OutcomeProposal(String kind, UUID investigationItemId) {
            this(kind, investigationItemId, List.of(), null, null, null, null,
                    null, null, null, null, List.of());
        }
        private OutcomeProposal withOwnership(String subject, String why, String name, String description) {
            return new OutcomeProposal(kind, investigationItemId, evidenceIds, reason, explanation,
                    knowledgeKind, claim, subject, why, name, description, conflictMembers);
        }
        private OutcomeProposal withClaim(String value, String candidateKind) {
            return new OutcomeProposal(kind, investigationItemId, evidenceIds, reason, explanation,
                    candidateKind, value, unresolvedSubject, whyCurrentParticipantCannotAnswer,
                    ownerName, ownerDescription, conflictMembers);
        }
    }
    public record ConflictMemberProposal(String claim, UUID evidenceId) {}
    public record ScopeAssessmentProposal(UUID evidenceId, String assessment,
            UUID missionBoundaryId, String rationale) {}
    public record NextAction(String kind, UUID targetInvestigationItemId, String question,
            String humanContext, UUID sourceQuestionId, UUID sourceEvidenceId,
            String paraphraseReason, Progress progress, String completionRecap,
            List<UnresolvedReference> unresolvedReferences) {
        public NextAction(String kind, UUID targetInvestigationItemId, String question,
                String humanContext, UUID sourceQuestionId, UUID sourceEvidenceId,
                String paraphraseReason, Progress progress) {
            this(kind, targetInvestigationItemId, question, humanContext, sourceQuestionId,
                    sourceEvidenceId, paraphraseReason, progress, null, List.of());
        }
        public NextAction(String kind, UUID targetInvestigationItemId, String question,
                String humanContext, Progress progress) {
            this(kind, targetInvestigationItemId, question, humanContext, null, null, null, progress,
                    null, List.of());
        }
    }
    public record UnresolvedReference(UUID investigationItemId, String kind) {}
    public record Progress(String covered, String current, String remaining) {}
    public record Event(UUID id, String type, UUID questionId, String question, String humanContext,
            Integer coveredCount, Integer totalRequired, String coveredText, String currentText,
            String remainingText, UUID completionProposalId, String completionRecap) {}
    private record Claim(UUID id, UUID organisationId, String status) {}
    private record Mission(String objective, String desiredOutcome, String completionCriteria,
            String expectedCommitment) {}
    private record SessionState(String status, int revision, UUID activeQuestionId) {}
    private record ResultState(UUID id, String status) {}
    private record EvidenceState(UUID id, String participationSignal, String answer) {}
    private record PreparedOutcome(UUID id, UUID resultId, OutcomeProposal proposal,
            List<UUID> evidenceIds, String explanation, List<ConflictMemberProposal> members,
            boolean terminal) {}
    private record PreparedAssessment(UUID id, UUID itemId, UUID evidenceId,
            String assessment, UUID boundaryId, String rationale) {}
    private record QuestionPlan(String kind, UUID clarifiesQuestionId,
            UUID sourceEvidenceId, String paraphraseReason) {}
    private record Target(String priority, boolean required) {}
    private record RunState(String status, UUID leaseOwner, int modelAttempts, int processRestarts) {}
    private record StoredCheckpoint(UUID id, UUID sessionId, UUID missionId, int expectedRevision,
            String runtimeVersion, String keyId, byte[] nonce, byte[] ciphertext) {}
    private record FailedRun(UUID id, UUID organisationId, UUID discoveryId, UUID sessionId,
            String workKind) {}
}
