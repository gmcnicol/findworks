package com.findworks.interview;

import com.findworks.security.PilotTenant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InterviewRuntimeRepository {

    private static final Set<String> PRIORITIES = Set.of("high", "medium", "low");
    private final JdbcClient jdbc;
    private final PilotTenant tenant;

    InterviewRuntimeRepository(JdbcClient jdbc, PilotTenant tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Transactional
    public Work claimNext() {
        tenant.select();
        var id = jdbc.sql("""
                SELECT id FROM interview_runtime_runs
                WHERE attempts < 3 AND available_at <= now()
                  AND (status = 'queued' OR (status = 'running' AND lease_until < now()))
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED LIMIT 1
                """).query(UUID.class).optional();
        if (id.isEmpty()) {
            return null;
        }
        return jdbc.sql("""
                UPDATE interview_runtime_runs
                SET status = 'running', attempts = attempts + 1,
                    lease_until = now() + interval '2 minutes', updated_at = now(), error_code = NULL
                WHERE id = ?
                RETURNING id, organisation_id, interview_session_id, interview_mission_id,
                          trigger, evidence_id, expected_revision, attempts
                """).param(id.get()).query((rs, ignored) -> new Work(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("interview_session_id", UUID.class),
                        rs.getObject("interview_mission_id", UUID.class),
                        rs.getString("trigger"), rs.getObject("evidence_id", UUID.class),
                        rs.getInt("expected_revision"), rs.getInt("attempts"))).single();
    }

    @Transactional(readOnly = true)
    public Context context(Work work) {
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
                  AND r.organisation_id = ? AND r.status = 'running'
                  AND s.status = 'active' AND s.revision = r.expected_revision
                  AND s.active_question_id IS NULL AND m.approved_at IS NOT NULL AND d.status = 'active'
                  AND ((r.trigger = 'session_start' AND r.evidence_id IS NULL)
                    OR (r.trigger = 'accepted_evidence' AND EXISTS (
                        SELECT 1 FROM evidence e
                        WHERE e.id = r.evidence_id AND e.interview_session_id = r.interview_session_id
                          AND e.organisation_id = r.organisation_id
                          AND e.source_type = 'interviewee_answer')))
                """).params(work.id(), work.sessionId(), work.missionId(), work.organisationId())
                .query((rs, ignored) -> new Mission(
                        rs.getString("objective"), rs.getString("desired_outcome"),
                        rs.getString("completion_criteria"), rs.getString("expected_commitment")))
                .optional().orElseThrow(() -> new IllegalStateException("Interview runtime scope is stale."));
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
                work.trigger(), work.evidenceId(),
                mission.objective(), mission.desiredOutcome(), sharedContext, boundaries, terminology,
                mission.completionCriteria(), mission.expectedCommitment(), openingGuidance, items, conversation);
    }

    @Transactional
    public Event complete(Work work, Submission submission) {
        tenant.select();
        var status = jdbc.sql("""
                SELECT status FROM interview_runtime_runs
                WHERE id = ? AND interview_session_id = ? AND interview_mission_id = ?
                  AND organisation_id = ? FOR UPDATE
                """).params(work.id(), work.sessionId(), work.missionId(), work.organisationId())
                .query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("Interview runtime Run no longer exists."));
        if ("committed".equals(status)) {
            return event(work.id());
        }
        if (!"running".equals(status)) {
            throw new IllegalStateException("Interview runtime Run is not running.");
        }
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
                )
                SELECT ?, r.organisation_id, r.discovery_id, r.interview_session_id,
                       r.interview_mission_id, ?, ?, ?, ?
                FROM interview_runtime_runs r WHERE r.id = ?
                """).params(questionId, action.targetInvestigationItemId(), sequence, question, context, work.id())
                .update();
        var eventId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO interview_application_events (
                    id, organisation_id, discovery_id, interview_session_id, runtime_run_id,
                    question_id, event_type, covered_count, total_required,
                    covered_text, current_text, remaining_text
                )
                SELECT ?, r.organisation_id, r.discovery_id, r.interview_session_id, r.id,
                       ?, 'question_ready', ?, ?, ?, ?, ?
                FROM interview_runtime_runs r WHERE r.id = ?
                """).params(eventId, questionId, coveredCount, totalRequired,
                covered, current, remaining, work.id()).update();
        var advanced = jdbc.sql("""
                UPDATE interview_sessions
                SET active_question_id = ?, revision = revision + 1
                WHERE id = ? AND revision = ? AND active_question_id IS NULL AND status = 'active'
                """).params(questionId, work.sessionId(), work.expectedRevision()).update();
        if (advanced != 1) {
            throw new IllegalArgumentException("Interview Session changed before the question committed.");
        }
        jdbc.sql("""
                UPDATE interview_runtime_runs
                SET status = 'committed', lease_until = NULL, updated_at = now()
                WHERE id = ?
                """).param(work.id()).update();
        tenant.auditSystem("interview_question_ready", "interview_session", work.sessionId());
        return new Event(eventId, questionId, question, context, coveredCount,
                totalRequired, covered, current, remaining);
    }

    @Transactional
    public void fail(Work work) {
        tenant.select();
        jdbc.sql("""
                UPDATE interview_runtime_runs
                SET status = CASE WHEN attempts >= 3 THEN 'failed' ELSE 'queued' END,
                    available_at = now() + interval '1 second', lease_until = NULL,
                    updated_at = now(), error_code = 'runtime_error'
                WHERE id = ? AND status = 'running'
                """).param(work.id()).update();
        if (work.attempts() >= 3) {
            tenant.auditSystem("interview_runtime_failed", "interview_session", work.sessionId());
        }
    }

    private Event event(UUID runId) {
        return jdbc.sql("""
                SELECT e.id event_id, q.id question_id, q.question, q.human_context,
                       e.covered_count, e.total_required, e.covered_text, e.current_text, e.remaining_text
                FROM interview_application_events e
                JOIN interview_questions q ON q.id = e.question_id
                WHERE e.runtime_run_id = ?
                """).param(runId).query((rs, ignored) -> new Event(
                        rs.getObject("event_id", UUID.class), rs.getObject("question_id", UUID.class),
                        rs.getString("question"), rs.getString("human_context"),
                        rs.getInt("covered_count"), rs.getInt("total_required"),
                        rs.getString("covered_text"), rs.getString("current_text"),
                        rs.getString("remaining_text"))).single();
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

    public record Work(UUID id, UUID organisationId, UUID sessionId, UUID missionId,
            String trigger, UUID evidenceId, int expectedRevision, int attempts) {}
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
    public record Event(UUID id, UUID questionId, String question, String humanContext,
            int coveredCount, int totalRequired, String coveredText, String currentText, String remainingText) {}
    private record Mission(String objective, String desiredOutcome, String completionCriteria,
            String expectedCommitment) {}
    private record SessionState(String status, int revision, UUID activeQuestionId) {}
}
