package com.findworks.interview;

import com.findworks.security.PilotTenant;
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
                          trigger, evidence_id, source_question_id, expected_revision, attempts
                """).param(id.get()).query((rs, ignored) -> new Work(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("interview_session_id", UUID.class),
                        rs.getObject("interview_mission_id", UUID.class),
                        rs.getString("trigger"), rs.getObject("evidence_id", UUID.class),
                        rs.getObject("source_question_id", UUID.class),
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
                          AND e.source_type = 'interviewee_answer'))
                    OR (r.trigger = 'clarification_request' AND EXISTS (
                        SELECT 1 FROM interview_questions q
                        WHERE q.id = r.source_question_id
                          AND q.interview_session_id = r.interview_session_id
                          AND q.organisation_id = r.organisation_id AND q.answered_at IS NULL)))
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
                LEFT JOIN evidence e ON e.question_id = q.id AND e.source_type = 'interviewee_answer'
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
        var outcomes = validateOutcomes(work, submission.outcomes());
        var assessments = validateAssessments(work, submission.scopeAssessments());
        commitOutcomes(work, outcomes);
        commitAssessments(work, assessments);
        var action = submission.nextAction();
        var targetPriority = jdbc.sql("""
                SELECT i.priority FROM investigation_items i
                LEFT JOIN investigation_results r ON r.investigation_item_id = i.id
                    AND r.interview_session_id = ?
                WHERE i.id = ? AND i.interview_mission_id = ? AND i.organisation_id = ? AND i.required
                  AND coalesce(r.status, 'unaddressed') <> 'explicit_outcome'
                """).params(work.sessionId(), action.targetInvestigationItemId(),
                work.missionId(), work.organisationId())
                .query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Question target is outside the Mission."));
        var highestPriority = jdbc.sql("""
                SELECT i.priority FROM investigation_items i
                LEFT JOIN investigation_results r ON r.investigation_item_id = i.id
                    AND r.interview_session_id = ?
                WHERE i.interview_mission_id = ? AND i.required
                  AND coalesce(r.status, 'unaddressed') <> 'explicit_outcome'
                ORDER BY CASE i.priority WHEN 'high' THEN 1 WHEN 'medium' THEN 2 ELSE 3 END, i.position
                LIMIT 1
                """).params(work.sessionId(), work.missionId()).query(String.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("No unresolved required area remains."));
        if (!targetPriority.equals(highestPriority) || !PRIORITIES.contains(targetPriority)) {
            throw new IllegalArgumentException("Question target is not in the highest-priority required area.");
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
                            evidenceIds, null, List.of(), false);
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
                  AND e.source_type = 'interviewee_answer'
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
                      AND organisation_id = ? AND source_type = 'interviewee_answer'
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
                || !Set.of("ask_question", "ask_clarification", "ask_paraphrase_confirmation")
                        .contains(submission.nextAction().kind())
                || submission.nextAction().progress() == null) {
            throw new IllegalArgumentException("Pi returned an invalid Interview turn.");
        }
        if ("session_start".equals(work.trigger())
                && (!submission.outcomes().isEmpty() || !submission.scopeAssessments().isEmpty()
                    || !"ask_question".equals(submission.nextAction().kind()))
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

    public record Work(UUID id, UUID organisationId, UUID sessionId, UUID missionId,
            String trigger, UUID evidenceId, UUID sourceQuestionId, int expectedRevision, int attempts) {}
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
            String paraphraseReason, Progress progress) {
        public NextAction(String kind, UUID targetInvestigationItemId, String question,
                String humanContext, Progress progress) {
            this(kind, targetInvestigationItemId, question, humanContext, null, null, null, progress);
        }
    }
    public record Progress(String covered, String current, String remaining) {}
    public record Event(UUID id, UUID questionId, String question, String humanContext,
            int coveredCount, int totalRequired, String coveredText, String currentText, String remainingText) {}
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
}
