package com.findworks.interview;

import com.findworks.security.PilotTenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class InterviewRepository {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern COMMITMENT = Pattern.compile(
            "(?i)(\\d+)\\s*(minutes?|mins?|hours?|hrs?)");
    private final JdbcClient jdbc;
    private final PilotTenant tenant;
    private final InterviewCompletionEligibility completion;
    private final Clock clock;
    private final InterviewProperties properties;

    InterviewRepository(JdbcClient jdbc, PilotTenant tenant, InterviewCompletionEligibility completion,
            Clock clock, InterviewProperties properties) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.completion = completion;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional
    RedeemedInvitation redeem(String invitationToken) {
        tenant.select();
        var now = clock.instant();
        var invitation = jdbc.sql("""
                SELECT i.id, i.organisation_id, i.discovery_id, i.interview_mission_id, i.participant_id
                FROM invitations i
                JOIN interview_missions m ON m.id = i.interview_mission_id
                    AND m.discovery_id = i.discovery_id AND m.organisation_id = i.organisation_id
                JOIN discoveries d ON d.id = i.discovery_id AND d.organisation_id = i.organisation_id
                JOIN discovery_participants p ON p.id = i.participant_id
                    AND p.discovery_id = i.discovery_id AND p.organisation_id = i.organisation_id
                WHERE i.token_hash = ? AND i.delivery_status = 'provider_accepted'
                  AND i.revoked_at IS NULL AND i.redeemed_at IS NULL AND i.expires_at > ?
                  AND m.status = 'approved' AND d.status = 'active'
                FOR UPDATE OF i
                """).params(hash(invitationToken), timestamp(now)).query((rs, ignored) -> new Invitation(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("discovery_id", UUID.class),
                        rs.getObject("interview_mission_id", UUID.class),
                        rs.getObject("participant_id", UUID.class))).optional()
                .orElseThrow(InterviewAccessDeniedException::new);

        var session = jdbc.sql("""
                SELECT id, participant_id FROM interview_sessions
                WHERE interview_mission_id = ? FOR UPDATE
                """).param(invitation.missionId()).query((rs, ignored) -> new Session(
                        rs.getObject("id", UUID.class), rs.getObject("participant_id", UUID.class))).optional();
        UUID sessionId;
        if (session.isPresent()) {
            if (!invitation.participantId().equals(session.get().participantId())) {
                throw new InterviewAccessDeniedException();
            }
            sessionId = session.get().id();
        } else {
            sessionId = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO interview_sessions (
                        id, organisation_id, discovery_id, interview_mission_id, participant_id
                    ) VALUES (?, ?, ?, ?, ?)
                    """).params(sessionId, invitation.organisationId(), invitation.discoveryId(),
                    invitation.missionId(), invitation.participantId()).update();
        }

        jdbc.sql("""
                UPDATE interview_access_grants SET revoked_at = COALESCE(revoked_at, ?)
                WHERE interview_session_id = ? AND revoked_at IS NULL
                """).params(timestamp(now), sessionId).update();
        var accessToken = randomToken();
        var expiresAt = now.plus(Duration.ofDays(7));
        jdbc.sql("""
                INSERT INTO interview_access_grants (
                    id, organisation_id, interview_session_id, participant_id, token_hash, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?)
                """).params(UUID.randomUUID(), invitation.organisationId(), sessionId,
                invitation.participantId(), hash(accessToken), timestamp(expiresAt)).update();
        var consumed = jdbc.sql("""
                UPDATE invitations SET redeemed_at = ?, delivery_status = 'redeemed'
                WHERE id = ? AND redeemed_at IS NULL AND delivery_status = 'provider_accepted'
                """).params(timestamp(now), invitation.id()).update();
        if (consumed != 1) {
            throw new InterviewAccessDeniedException();
        }
        tenant.auditSystem("invitation_redeemed", "invitation", invitation.id());
        tenant.auditSystem("interview_access_granted", "interview_session", sessionId);
        return new RedeemedInvitation(accessToken, expiresAt);
    }

    @Transactional(readOnly = true)
    AccessView interview(String accessToken) {
        requireToken(accessToken);
        tenant.select();
        return jdbc.sql("""
                SELECT s.status, p.intended_name, o.name organisation_name, o.retention_days,
                       u.email investigator_email,
                       m.objective, m.expected_commitment, m.data_use_summary,
                       s.revision, s.active_seconds, s.active_started_at,
                       s.commitment_acknowledged_at, q.id question_id, q.question, q.human_context,
                       proposal.id completion_proposal_id, proposal.recap completion_recap,
                       e.covered_count, e.total_required, e.covered_text, e.current_text, e.remaining_text,
                       latest_evidence.id latest_evidence_id,
                       latest_run.status runtime_status,
                       EXISTS (SELECT 1 FROM evidence accepted
                               WHERE accepted.interview_session_id = s.id
                                 AND accepted.source_type IN (
                                     'interviewee_answer', 'interviewee_answer_revision'
                                 )) answer_saved,
                       coalesce(latest_run.trigger = 'clarification_request', false) clarification_requested
                FROM interview_access_grants g
                JOIN interview_sessions s ON s.id = g.interview_session_id
                    AND s.participant_id = g.participant_id AND s.organisation_id = g.organisation_id
                JOIN discovery_participants p ON p.id = s.participant_id
                    AND p.discovery_id = s.discovery_id AND p.organisation_id = s.organisation_id
                JOIN interview_missions m ON m.id = s.interview_mission_id
                    AND m.discovery_id = s.discovery_id AND m.organisation_id = s.organisation_id
                JOIN discoveries d ON d.id = s.discovery_id AND d.organisation_id = s.organisation_id
                JOIN memberships owner ON owner.id = d.owner_membership_id
                    AND owner.organisation_id = d.organisation_id AND owner.role = 'investigator'
                JOIN users u ON u.id = owner.user_id AND u.email_verified_at IS NOT NULL
                JOIN organisations o ON o.id = d.organisation_id
                LEFT JOIN interview_questions q ON q.id = s.active_question_id
                    AND q.interview_session_id = s.id AND q.organisation_id = s.organisation_id
                LEFT JOIN interview_application_events e ON e.question_id = q.id
                    AND e.interview_session_id = s.id AND e.organisation_id = s.organisation_id
                LEFT JOIN interview_completion_proposals proposal
                    ON proposal.id = s.current_completion_proposal_id
                    AND proposal.interview_session_id = s.id
                    AND proposal.organisation_id = s.organisation_id
                LEFT JOIN LATERAL (
                    SELECT r.status, r.trigger FROM interview_runtime_runs r
                    WHERE r.interview_session_id = s.id ORDER BY r.created_at DESC LIMIT 1
                ) latest_run ON true
                LEFT JOIN LATERAL (
                    SELECT accepted.id
                    FROM evidence accepted
                    LEFT JOIN evidence revision ON revision.revises_evidence_id = accepted.id
                    WHERE accepted.interview_session_id = s.id
                      AND accepted.source_type IN ('interviewee_answer', 'interviewee_answer_revision')
                      AND revision.id IS NULL
                    ORDER BY accepted.created_at DESC, accepted.id DESC LIMIT 1
                ) latest_evidence ON true
                WHERE g.token_hash = ? AND g.revoked_at IS NULL AND g.expires_at > ?
                  AND d.status = 'active' AND m.approved_at IS NOT NULL
                  AND (s.status <> 'not_started' OR m.status = 'approved')
                """).params(hash(accessToken), timestamp(clock.instant())).query((rs, ignored) -> {
                    var investigatorEmail = rs.getString("investigator_email");
                    var question = rs.getString("question");
                    var runtimeStatus = rs.getString("runtime_status");
                    var status = rs.getString("status");
                    var live = "active".equals(status) || "in_progress".equals(status);
                    var proposalId = rs.getObject("completion_proposal_id", UUID.class);
                    var runtimeState = !live ? null : proposalId != null ? "completion_confirmation_ready"
                            : question != null ? "question_ready"
                            : "failed".equals(runtimeStatus) ? "runtime_failed" : "working";
                    var activeStarted = rs.getTimestamp("active_started_at");
                    var activeSeconds = rs.getLong("active_seconds") + (activeStarted == null ? 0
                            : Math.max(0, Duration.between(activeStarted.toInstant(), clock.instant()).toSeconds()));
                    var offerEndChoice = question != null && rs.getTimestamp("commitment_acknowledged_at") == null
                            && nearCommitment(rs.getString("expected_commitment"), activeSeconds);
                    return new AccessView(status, rs.getString("intended_name"),
                            rs.getString("organisation_name"), investigatorEmail, rs.getString("objective"),
                            rs.getString("expected_commitment"), rs.getString("data_use_summary"),
                            rs.getInt("retention_days"), properties.contactOr(investigatorEmail),
                            runtimeState, rs.getInt("revision"), rs.getObject("question_id", UUID.class),
                            question, rs.getString("human_context"), rs.getBoolean("answer_saved"),
                            rs.getBoolean("clarification_requested"),
                            rs.getObject("covered_count", Integer.class),
                            rs.getObject("total_required", Integer.class),
                            rs.getString("covered_text"), rs.getString("current_text"),
                            rs.getString("remaining_text"), rs.getObject("latest_evidence_id", UUID.class),
                            offerEndChoice, proposalId, rs.getString("completion_recap"));
                }).optional().orElseThrow(InterviewAccessDeniedException::new);
    }

    @Transactional
    void start(String accessToken) {
        requireToken(accessToken);
        tenant.select();
        var session = jdbc.sql("""
                SELECT s.id, s.status, s.revision, s.organisation_id, s.discovery_id,
                       s.interview_mission_id
                FROM interview_access_grants g
                JOIN interview_sessions s ON s.id = g.interview_session_id
                    AND s.participant_id = g.participant_id AND s.organisation_id = g.organisation_id
                JOIN interview_missions m ON m.id = s.interview_mission_id
                    AND m.discovery_id = s.discovery_id AND m.organisation_id = s.organisation_id
                JOIN discoveries d ON d.id = s.discovery_id AND d.organisation_id = s.organisation_id
                WHERE g.token_hash = ? AND g.revoked_at IS NULL AND g.expires_at > ?
                  AND d.status = 'active' AND m.approved_at IS NOT NULL
                  AND (s.status <> 'not_started' OR m.status = 'approved')
                FOR UPDATE OF s
                """).params(hash(accessToken), timestamp(clock.instant())).query((rs, ignored) -> new SessionState(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getInt("revision"),
                        rs.getObject("organisation_id", UUID.class), rs.getObject("discovery_id", UUID.class),
                        rs.getObject("interview_mission_id", UUID.class))).optional()
                .orElseThrow(InterviewAccessDeniedException::new);
        if (!"not_started".equals(session.status())) {
            return;
        }
        var expectedRevision = session.revision() + 1;
        jdbc.sql("""
                UPDATE interview_sessions
                SET status = 'active', started_at = ?, active_started_at = ?, revision = revision + 1
                WHERE id = ? AND status = 'not_started'
                """).params(timestamp(clock.instant()), timestamp(clock.instant()), session.id()).update();
        jdbc.sql("""
                INSERT INTO interview_runtime_runs (
                    id, organisation_id, discovery_id, interview_session_id,
                    interview_mission_id, trigger, expected_revision
                ) VALUES (?, ?, ?, ?, ?, 'session_start', ?)
                """).params(UUID.randomUUID(), session.organisationId(), session.discoveryId(), session.id(),
                session.missionId(), expectedRevision).update();
        tenant.auditSystem("interview_session_started", "interview_session", session.id());
    }

    @Transactional
    void answer(String accessToken, UUID questionId, int expectedRevision, String submittedAnswer) {
        acceptAnswer(accessToken, questionId, expectedRevision, submittedAnswer, null);
    }

    @Transactional
    void answerChoice(String accessToken, UUID questionId, int expectedRevision, String choice, String owner) {
        switch (choice == null ? "" : choice) {
            case "did_not_know" -> acceptAnswer(
                    accessToken, questionId, expectedRevision, "I do not know.", "did_not_know");
            case "declined" -> acceptAnswer(
                    accessToken, questionId, expectedRevision, "I prefer not to answer.", "declined");
            case "other_owner" -> acceptAnswer(accessToken, questionId, expectedRevision,
                    "Someone else may know: " + ownerText(owner), "other_owner");
            default -> throw new IllegalArgumentException("Choose a valid response.");
        }
    }

    @Transactional
    void clarify(String accessToken, UUID questionId, int expectedRevision) {
        requireToken(accessToken);
        tenant.select();
        var session = answerSession(accessToken);
        if (!"active".equals(session.status()) || session.revision() != expectedRevision
                || !questionId.equals(session.activeQuestionId())) {
            var queued = jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM interview_runtime_runs
                        WHERE interview_session_id = ? AND trigger = 'clarification_request'
                          AND source_question_id = ? AND expected_revision = ?)
                    """).params(session.id(), questionId, expectedRevision + 1).query(Boolean.class).single();
            if (queued) {
                return;
            }
            throw new IllegalArgumentException("This Interview question is no longer active.");
        }
        var questionExists = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM interview_questions
                    WHERE id = ? AND interview_session_id = ? AND interview_mission_id = ?
                      AND organisation_id = ? AND answered_at IS NULL)
                """).params(questionId, session.id(), session.missionId(), session.organisationId())
                .query(Boolean.class).single();
        if (!questionExists) {
            throw new IllegalArgumentException("This Interview question is no longer active.");
        }
        var nextRevision = expectedRevision + 1;
        var advanced = jdbc.sql("""
                UPDATE interview_sessions SET active_question_id = NULL, revision = revision + 1
                WHERE id = ? AND revision = ? AND active_question_id = ? AND status = 'active'
                """).params(session.id(), expectedRevision, questionId).update();
        if (advanced != 1) {
            throw new IllegalArgumentException("Interview Session changed before clarification committed.");
        }
        jdbc.sql("""
                INSERT INTO interview_runtime_runs (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    trigger, source_question_id, expected_revision
                ) VALUES (?, ?, ?, ?, ?, 'clarification_request', ?, ?)
                """).params(UUID.randomUUID(), session.organisationId(), session.discoveryId(), session.id(),
                session.missionId(), questionId, nextRevision).update();
        tenant.auditSystem("interview_clarification_requested", "interview_session", session.id());
    }

    private void acceptAnswer(String accessToken, UUID questionId, int expectedRevision,
            String submittedAnswer, String participationSignal) {
        requireToken(accessToken);
        var answer = answerText(submittedAnswer);
        tenant.select();
        var session = answerSession(accessToken);
        if (!"active".equals(session.status()) || session.revision() != expectedRevision
                || !questionId.equals(session.activeQuestionId())) {
            var accepted = jdbc.sql("""
                    SELECT answer, participation_signal FROM evidence
                    WHERE interview_session_id = ? AND question_id = ? AND source_type = 'interviewee_answer'
                    """).params(session.id(), questionId).query((rs, ignored) -> new AcceptedAnswer(
                            rs.getString("answer"), rs.getString("participation_signal"))).optional();
            if (accepted.isPresent() && accepted.get().answer().equals(answer)
                    && java.util.Objects.equals(accepted.get().participationSignal(), participationSignal)) {
                return;
            }
            throw new IllegalArgumentException("This Interview question is no longer active.");
        }
        acceptAnswer(session, questionId, expectedRevision, answer, participationSignal);
    }

    private AnswerState answerSession(String accessToken) {
        var session = jdbc.sql("""
                SELECT s.id, s.status, s.revision, s.organisation_id, s.discovery_id,
                       s.interview_mission_id, s.participant_id, s.active_question_id
                FROM interview_access_grants g
                JOIN interview_sessions s ON s.id = g.interview_session_id
                    AND s.participant_id = g.participant_id AND s.organisation_id = g.organisation_id
                JOIN interview_missions m ON m.id = s.interview_mission_id
                    AND m.discovery_id = s.discovery_id AND m.organisation_id = s.organisation_id
                JOIN discoveries d ON d.id = s.discovery_id AND d.organisation_id = s.organisation_id
                WHERE g.token_hash = ? AND g.revoked_at IS NULL AND g.expires_at > ?
                  AND d.status = 'active' AND m.approved_at IS NOT NULL
                FOR UPDATE OF s
                """).params(hash(accessToken), timestamp(clock.instant())).query((rs, ignored) -> new AnswerState(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getInt("revision"),
                        rs.getObject("organisation_id", UUID.class), rs.getObject("discovery_id", UUID.class),
                        rs.getObject("interview_mission_id", UUID.class),
                        rs.getObject("participant_id", UUID.class),
                        rs.getObject("active_question_id", UUID.class))).optional()
                .orElseThrow(InterviewAccessDeniedException::new);
        return session;
    }

    private void acceptAnswer(AnswerState session, UUID questionId, int expectedRevision,
            String answer, String participationSignal) {
        var itemId = jdbc.sql("""
                SELECT investigation_item_id FROM interview_questions
                WHERE id = ? AND interview_session_id = ? AND interview_mission_id = ?
                  AND organisation_id = ? AND answered_at IS NULL
                """).params(questionId, session.id(), session.missionId(), session.organisationId())
                .query(UUID.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("This Interview question is no longer active."));
        var evidenceId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO evidence (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    participant_id, question_id, investigation_item_id, source_type,
                    participation_signal, answer
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'interviewee_answer', ?, ?)
                """).params(evidenceId, session.organisationId(), session.discoveryId(), session.id(),
                session.missionId(), session.participantId(), questionId, itemId,
                participationSignal, answer).update();
        var answered = jdbc.sql("""
                UPDATE interview_questions SET answered_at = ?
                WHERE id = ? AND interview_session_id = ? AND answered_at IS NULL
                """).params(timestamp(clock.instant()), questionId, session.id()).update();
        if (answered != 1) {
            throw new IllegalArgumentException("This Interview question is no longer active.");
        }
        jdbc.sql("""
                INSERT INTO investigation_results (
                    id, organisation_id, discovery_id, interview_session_id,
                    interview_mission_id, investigation_item_id, status
                ) VALUES (?, ?, ?, ?, ?, ?, 'exploring')
                ON CONFLICT (interview_session_id, investigation_item_id) DO NOTHING
                """).params(UUID.randomUUID(), session.organisationId(), session.discoveryId(), session.id(),
                session.missionId(), itemId).update();
        var nextRevision = expectedRevision + 1;
        var advanced = jdbc.sql("""
                UPDATE interview_sessions SET active_question_id = NULL, revision = revision + 1
                WHERE id = ? AND revision = ? AND active_question_id = ? AND status = 'active'
                """).params(session.id(), expectedRevision, questionId).update();
        if (advanced != 1) {
            throw new IllegalArgumentException("Interview Session changed before the answer committed.");
        }
        jdbc.sql("""
                INSERT INTO interview_runtime_runs (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    trigger, evidence_id, expected_revision
                ) VALUES (?, ?, ?, ?, ?, 'accepted_evidence', ?, ?)
                """).params(UUID.randomUUID(), session.organisationId(), session.discoveryId(), session.id(),
                session.missionId(), evidenceId, nextRevision).update();
        tenant.auditSystem("interview_answer_accepted", "interview_session", session.id());
    }

    @Transactional
    void pause(String accessToken, int expectedRevision) {
        var session = participantState(accessToken);
        requireState(session, expectedRevision, "active");
        var now = clock.instant();
        var changed = jdbc.sql("""
                UPDATE interview_sessions
                SET status = 'paused', active_seconds = active_seconds
                        + greatest(0, extract(epoch from (? - active_started_at))::bigint),
                    active_started_at = NULL, revision = revision + 1
                WHERE id = ? AND status = 'active' AND revision = ?
                """).params(timestamp(now), session.id(), expectedRevision).update();
        changed(changed);
        cancelRuntime(session.id(), now);
        tenant.auditSystem("interview_session_paused", "interview_session", session.id());
    }

    @Transactional
    void resume(String accessToken, int expectedRevision) {
        var session = participantState(accessToken);
        requireState(session, expectedRevision, "paused");
        var nextRevision = expectedRevision + 1;
        var changed = jdbc.sql("""
                UPDATE interview_sessions
                SET status = 'active', active_started_at = ?, revision = revision + 1
                WHERE id = ? AND status = 'paused' AND revision = ?
                """).params(timestamp(clock.instant()), session.id(), expectedRevision).update();
        changed(changed);
        if (session.activeQuestionId() == null) {
            insertRuntime(session, "resume", null, null, nextRevision);
        }
        tenant.auditSystem("interview_session_resumed", "interview_session", session.id());
    }

    @Transactional
    void revise(String accessToken, UUID evidenceId, int expectedRevision, String submittedAnswer) {
        var answer = answerText(submittedAnswer);
        var session = participantState(accessToken);
        requireState(session, expectedRevision, "active");
        var evidence = jdbc.sql("""
                SELECT e.question_id, e.investigation_item_id, e.answer
                FROM evidence e
                LEFT JOIN evidence successor ON successor.revises_evidence_id = e.id
                WHERE e.id = ? AND e.interview_session_id = ? AND e.participant_id = ?
                  AND e.organisation_id = ?
                  AND e.source_type IN ('interviewee_answer', 'interviewee_answer_revision')
                  AND successor.id IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM evidence later
                      LEFT JOIN evidence later_successor ON later_successor.revises_evidence_id = later.id
                      WHERE later.interview_session_id = e.interview_session_id
                        AND later.source_type IN ('interviewee_answer', 'interviewee_answer_revision')
                        AND later_successor.id IS NULL
                        AND (later.created_at, later.id) > (e.created_at, e.id)
                  )
                """).params(evidenceId, session.id(), session.participantId(), session.organisationId())
                .query((rs, ignored) -> new EvidenceState(
                        rs.getObject("question_id", UUID.class),
                        rs.getObject("investigation_item_id", UUID.class), rs.getString("answer")))
                .optional().orElseThrow(() -> new IllegalArgumentException("Only the latest response can be revised."));
        if (evidence.answer().equals(answer)) {
            throw new IllegalArgumentException("The revised response must be different.");
        }
        var revisionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO evidence (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    participant_id, question_id, investigation_item_id, source_type, answer,
                    revises_evidence_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'interviewee_answer_revision', ?, ?)
                """).params(revisionId, session.organisationId(), session.discoveryId(), session.id(),
                session.missionId(), session.participantId(), evidence.questionId(), evidence.itemId(), answer,
                evidenceId).update();
        jdbc.sql("""
                UPDATE investigation_results SET status = 'exploring'
                WHERE interview_session_id = ? AND investigation_item_id = ?
                  AND organisation_id = ? AND status = 'explicit_outcome'
                """).params(session.id(), evidence.itemId(), session.organisationId()).update();
        var nextRevision = expectedRevision + 1;
        var changed = jdbc.sql("""
                UPDATE interview_sessions SET active_question_id = NULL, revision = revision + 1
                WHERE id = ? AND status = 'active' AND revision = ?
                """).params(session.id(), expectedRevision).update();
        changed(changed);
        cancelRuntime(session.id(), clock.instant());
        insertRuntime(session, "accepted_evidence", revisionId, null, nextRevision);
        tenant.auditSystem("interview_answer_revised", "interview_session", session.id());
    }

    @Transactional
    void continueInterview(String accessToken, int expectedRevision) {
        var session = participantState(accessToken);
        requireState(session, expectedRevision, "active");
        var changed = jdbc.sql("""
                UPDATE interview_sessions
                SET commitment_acknowledged_at = ?, revision = revision + 1
                WHERE id = ? AND status = 'active' AND revision = ?
                  AND commitment_acknowledged_at IS NULL
                """).params(timestamp(clock.instant()), session.id(), expectedRevision).update();
        changed(changed);
        tenant.auditSystem("interview_commitment_continued", "interview_session", session.id());
    }

    @Transactional
    void continueAfterCompletionProposal(String accessToken, UUID proposalId, int expectedRevision) {
        var session = participantState(accessToken);
        requireCurrentProposal(session, proposalId, expectedRevision);
        var now = clock.instant();
        changed(jdbc.sql("""
                UPDATE interview_completion_proposals SET status = 'continued', decided_at = ?
                WHERE id = ? AND interview_session_id = ? AND organisation_id = ? AND status = 'pending'
                """).params(timestamp(now), proposalId, session.id(), session.organisationId()).update());
        changed(jdbc.sql("""
                UPDATE interview_sessions
                SET current_completion_proposal_id = NULL, revision = revision + 1
                WHERE id = ? AND revision = ? AND status = 'active'
                  AND current_completion_proposal_id = ?
                """).params(session.id(), expectedRevision, proposalId).update());
        cancelRuntime(session.id(), now);
        insertRuntime(session, "resume", null, null, expectedRevision + 1);
        tenant.auditSystem("interview_completion_continued", "interview_session", session.id());
    }

    @Transactional
    void finish(String accessToken, UUID proposalId, int expectedRevision) {
        var session = participantState(accessToken);
        requireCurrentProposal(session, proposalId, expectedRevision);
        completion.requireEligible(session.id(), session.missionId(), session.organisationId());
        var current = completion.unresolved(session.id(), session.missionId(), session.organisationId())
                .stream().map(InterviewCompletionEligibility.Unresolved::outcomeId).toList();
        var proposed = jdbc.sql("""
                SELECT outcome_id FROM interview_completion_unresolved_refs
                WHERE completion_proposal_id = ? AND interview_session_id = ? AND organisation_id = ?
                ORDER BY position
                """).params(proposalId, session.id(), session.organisationId()).query(UUID.class).list();
        if (!proposed.equals(current)) {
            throw new IllegalArgumentException("The completion recap is stale. Continue the Interview instead.");
        }
        var now = clock.instant();
        changed(jdbc.sql("""
                UPDATE interview_completion_proposals SET status = 'confirmed', decided_at = ?
                WHERE id = ? AND interview_session_id = ? AND organisation_id = ? AND status = 'pending'
                """).params(timestamp(now), proposalId, session.id(), session.organisationId()).update());
        changed(jdbc.sql("""
                UPDATE interview_sessions
                SET status = 'completed', completed_at = ?,
                    active_seconds = active_seconds + greatest(
                        0, extract(epoch from (? - active_started_at))::bigint),
                    active_started_at = NULL, active_question_id = NULL,
                    current_completion_proposal_id = NULL, revision = revision + 1
                WHERE id = ? AND revision = ? AND status = 'active'
                  AND current_completion_proposal_id = ?
                """).params(timestamp(now), timestamp(now), session.id(), expectedRevision, proposalId).update());
        cancelRuntime(session.id(), now);
        jdbc.sql("""
                INSERT INTO interview_runtime_runs (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    work_kind, trigger, expected_revision, generation
                ) VALUES (?, ?, ?, ?, ?, 'findings_extraction', 'findings_extraction', ?, 1)
                """).params(UUID.randomUUID(), session.organisationId(), session.discoveryId(), session.id(),
                session.missionId(), expectedRevision + 1).update();
        tenant.auditSystem("interview_session_completed", "interview_session", session.id());
    }

    private void requireCurrentProposal(ParticipantState session, UUID proposalId, int expectedRevision) {
        if (proposalId == null || session.revision() != expectedRevision || !"active".equals(session.status())
                || !proposalId.equals(session.currentCompletionProposalId())) {
            throw new IllegalArgumentException("This completion choice is stale. Refresh and try again.");
        }
        var pending = jdbc.sql("""
                SELECT count(*) FROM interview_completion_proposals
                WHERE id = ? AND interview_session_id = ? AND interview_mission_id = ?
                  AND organisation_id = ? AND status = 'pending'
                """).params(proposalId, session.id(), session.missionId(), session.organisationId())
                .query(Integer.class).single();
        if (pending != 1) {
            throw new IllegalArgumentException("This completion choice is no longer available.");
        }
    }

    @Transactional
    void endEarly(String accessToken, int expectedRevision, boolean confirmed) {
        if (!confirmed) {
            throw new IllegalArgumentException("Confirm that you want to finish this Interview now.");
        }
        var session = participantState(accessToken);
        if (session.revision() != expectedRevision
                || session.currentCompletionProposalId() != null
                || !("active".equals(session.status()) || "paused".equals(session.status()))) {
            throw new IllegalArgumentException("This Interview Session changed before it could end.");
        }
        var now = clock.instant();
        var changed = jdbc.sql("""
                UPDATE interview_sessions
                SET status = 'ended_early', ended_at = ?, active_seconds = active_seconds + CASE
                        WHEN active_started_at IS NULL THEN 0
                        ELSE greatest(0, extract(epoch from (? - active_started_at))::bigint) END,
                    active_started_at = NULL, active_question_id = NULL, revision = revision + 1
                WHERE id = ? AND revision = ? AND status IN ('active', 'paused')
                """).params(timestamp(now), timestamp(now), session.id(), expectedRevision).update();
        changed(changed);
        cancelRuntime(session.id(), now);
        tenant.auditSystem("interview_session_ended_early", "interview_session", session.id());
    }

    @Transactional(readOnly = true)
    MissionSession missionSession(UUID missionId, String email) {
        var investigator = tenant.investigator(email);
        return jdbc.sql("""
                SELECT s.id, s.status, s.revision, p.intended_name
                FROM interview_sessions s
                JOIN interview_missions m ON m.id = s.interview_mission_id
                    AND m.organisation_id = s.organisation_id
                JOIN discoveries d ON d.id = s.discovery_id AND d.organisation_id = s.organisation_id
                JOIN discovery_participants p ON p.id = s.participant_id
                    AND p.organisation_id = s.organisation_id
                WHERE s.interview_mission_id = ? AND d.owner_membership_id = ?
                """).params(missionId, investigator.membershipId()).query((rs, ignored) -> {
                    var sessionId = rs.getObject("id", UUID.class);
                    var remaining = jdbc.sql("""
                            SELECT i.knowledge_gap FROM investigation_items i
                            LEFT JOIN investigation_results r ON r.interview_session_id = ?
                                AND r.investigation_item_id = i.id
                            WHERE i.interview_mission_id = ? AND i.required
                              AND coalesce(r.status, 'unaddressed') <> 'explicit_outcome'
                            ORDER BY i.position
                            """).params(sessionId, missionId).query(String.class).list();
                    var followUp = jdbc.sql("""
                            SELECT DISTINCT i.knowledge_gap FROM investigation_items i
                            JOIN investigation_outcomes o ON o.investigation_item_id = i.id
                                AND o.interview_session_id = ?
                            JOIN investigation_results r ON r.id = o.investigation_result_id
                                AND r.status = 'explicit_outcome'
                            WHERE i.interview_mission_id = ? AND i.required
                              AND o.kind IN ('unknown', 'conflict', 'ownership_gap')
                              AND EXISTS (
                                SELECT 1 FROM investigation_outcome_evidence oe
                                JOIN evidence e ON e.id = oe.evidence_id
                                WHERE oe.outcome_id = o.id
                                  AND NOT EXISTS (
                                    SELECT 1 FROM evidence revision
                                    WHERE revision.revises_evidence_id = e.id
                                  )
                              )
                            ORDER BY i.knowledge_gap
                            """).params(sessionId, missionId).query(String.class).list();
                    return new MissionSession(sessionId, rs.getString("status"), rs.getInt("revision"),
                            rs.getString("intended_name"), remaining, followUp);
                }).optional().orElse(null);
    }

    @Transactional
    void terminate(UUID missionId, UUID sessionId, int expectedRevision, String email) {
        var investigator = tenant.investigator(email);
        var session = jdbc.sql("""
                SELECT s.id, s.status, s.revision, s.organisation_id,
                       s.current_completion_proposal_id
                FROM interview_sessions s
                JOIN discoveries d ON d.id = s.discovery_id AND d.organisation_id = s.organisation_id
                WHERE s.id = ? AND s.interview_mission_id = ? AND d.owner_membership_id = ?
                FOR UPDATE OF s
                """).params(sessionId, missionId, investigator.membershipId())
                .query((rs, ignored) -> new OwnerSession(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getInt("revision"), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("current_completion_proposal_id", UUID.class)))
                .optional().orElseThrow(() -> new IllegalArgumentException("Interview Session not found."));
        if (session.revision() != expectedRevision
                || !("active".equals(session.status()) || "paused".equals(session.status()))) {
            throw new IllegalArgumentException("Only an active or paused Interview Session can be terminated.");
        }
        var now = clock.instant();
        if (session.currentCompletionProposalId() != null) {
            jdbc.sql("""
                    UPDATE interview_completion_proposals SET status = 'withdrawn', decided_at = ?
                    WHERE id = ? AND interview_session_id = ? AND organisation_id = ? AND status = 'pending'
                    """).params(timestamp(now), session.currentCompletionProposalId(), session.id(),
                    session.organisationId()).update();
        }
        var changed = jdbc.sql("""
                UPDATE interview_sessions
                SET status = 'terminated', terminated_at = ?, active_seconds = active_seconds + CASE
                        WHEN active_started_at IS NULL THEN 0
                        ELSE greatest(0, extract(epoch from (? - active_started_at))::bigint) END,
                    active_started_at = NULL, active_question_id = NULL,
                    current_completion_proposal_id = NULL, revision = revision + 1
                WHERE id = ? AND revision = ? AND status IN ('active', 'paused')
                """).params(timestamp(now), timestamp(now), session.id(), expectedRevision).update();
        changed(changed);
        cancelRuntime(session.id(), now);
        jdbc.sql("""
                UPDATE invitations SET revoked_at = coalesce(revoked_at, ?),
                    delivery_status = CASE WHEN delivery_status = 'legacy_inert'
                        THEN delivery_status ELSE 'revoked' END
                WHERE interview_mission_id = ? AND revoked_at IS NULL
                """).params(timestamp(now), missionId).update();
        jdbc.sql("""
                UPDATE interview_access_grants SET revoked_at = coalesce(revoked_at, ?)
                WHERE interview_session_id = ? AND revoked_at IS NULL
                """).params(timestamp(now), session.id()).update();
        tenant.auditSystem("interview_session_terminated", "interview_session", session.id());
    }

    @Transactional
    void auditDenied() {
        tenant.select();
        tenant.auditSystemDenied("interview_access_denied", "interview_access");
    }

    private ParticipantState participantState(String accessToken) {
        requireToken(accessToken);
        tenant.select();
        return jdbc.sql("""
                SELECT s.id, s.status, s.revision, s.organisation_id, s.discovery_id,
                       s.interview_mission_id, s.participant_id, s.active_question_id,
                       s.current_completion_proposal_id
                FROM interview_access_grants g
                JOIN interview_sessions s ON s.id = g.interview_session_id
                    AND s.participant_id = g.participant_id AND s.organisation_id = g.organisation_id
                JOIN interview_missions m ON m.id = s.interview_mission_id
                    AND m.discovery_id = s.discovery_id AND m.organisation_id = s.organisation_id
                JOIN discoveries d ON d.id = s.discovery_id AND d.organisation_id = s.organisation_id
                WHERE g.token_hash = ? AND g.revoked_at IS NULL AND g.expires_at > ?
                  AND d.status = 'active' AND m.approved_at IS NOT NULL
                FOR UPDATE OF s
                """).params(hash(accessToken), timestamp(clock.instant())).query((rs, ignored) ->
                        new ParticipantState(rs.getObject("id", UUID.class), rs.getString("status"),
                                rs.getInt("revision"), rs.getObject("organisation_id", UUID.class),
                                rs.getObject("discovery_id", UUID.class),
                                rs.getObject("interview_mission_id", UUID.class),
                                rs.getObject("participant_id", UUID.class),
                                rs.getObject("active_question_id", UUID.class),
                                rs.getObject("current_completion_proposal_id", UUID.class)))
                .optional().orElseThrow(InterviewAccessDeniedException::new);
    }

    private void insertRuntime(ParticipantState session, String trigger, UUID evidenceId,
            UUID sourceQuestionId, int expectedRevision) {
        jdbc.sql("""
                INSERT INTO interview_runtime_runs (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    trigger, evidence_id, source_question_id, expected_revision
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(UUID.randomUUID(), session.organisationId(), session.discoveryId(), session.id(),
                session.missionId(), trigger, evidenceId, sourceQuestionId, expectedRevision).update();
    }

    private void cancelRuntime(UUID sessionId, Instant now) {
        jdbc.sql("""
                UPDATE runtime_credentials SET revoked_at = coalesce(revoked_at, ?)
                WHERE runtime_run_id IN (
                    SELECT id FROM interview_runtime_runs
                    WHERE interview_session_id = ? AND status IN ('queued', 'running')
                ) AND used_at IS NULL
                """).params(timestamp(now), sessionId).update();
        jdbc.sql("""
                UPDATE interview_runtime_attempts
                SET outcome = 'cancelled', finished_at = ?
                WHERE runtime_run_id IN (
                    SELECT id FROM interview_runtime_runs
                    WHERE interview_session_id = ? AND status = 'running'
                ) AND outcome = 'running'
                """).params(timestamp(now), sessionId).update();
        jdbc.sql("""
                UPDATE interview_runtime_runs
                SET status = 'cancelled', cancelled_at = ?, lease_until = NULL,
                    lease_owner = NULL, updated_at = ?
                WHERE interview_session_id = ? AND status IN ('queued', 'running')
                """).params(timestamp(now), timestamp(now), sessionId).update();
    }

    private static void requireState(ParticipantState session, int expectedRevision, String status) {
        if (session.revision() != expectedRevision || !status.equals(session.status())
                || session.currentCompletionProposalId() != null) {
            throw new IllegalArgumentException("This Interview Session changed. Refresh and try again.");
        }
    }

    private static void changed(int count) {
        if (count != 1) {
            throw new IllegalArgumentException("This Interview Session changed. Refresh and try again.");
        }
    }

    private static boolean nearCommitment(String commitment, long activeSeconds) {
        if (commitment == null) {
            return false;
        }
        var matcher = COMMITMENT.matcher(commitment);
        if (!matcher.find()) {
            return false;
        }
        try {
            var eightyPercentSeconds = matcher.group(2).toLowerCase().startsWith("h") ? 2_880L : 48L;
            return activeSeconds >= Math.multiplyExact(Long.parseLong(matcher.group(1)), eightyPercentSeconds);
        } catch (ArithmeticException ignored) {
            return false;
        }
    }

    private static void requireToken(String token) {
        if (token == null || token.isBlank() || token.length() > 500) {
            throw new InterviewAccessDeniedException();
        }
    }

    private static String answerText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Please enter an answer.");
        }
        var answer = value.trim();
        if (answer.length() > 10_000 || answer.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Answer must be 10,000 characters or fewer.");
        }
        return answer;
    }

    private static String ownerText(String value) {
        var owner = answerText(value);
        if (owner.length() > 300) {
            throw new IllegalArgumentException("Owner name or description must be 300 characters or fewer.");
        }
        return owner;
    }

    private static String randomToken() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String token) {
        requireToken(token);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    record RedeemedInvitation(String accessToken, Instant expiresAt) {}
    record AccessView(String status, String participantName, String organisationName, String investigatorEmail,
            String purpose, String expectedCommitment, String dataUseSummary, int retentionDays, String contact,
            String runtimeState, int revision, UUID questionId, String question, String humanContext,
            boolean answerSaved, boolean clarificationRequested,
            Integer coveredCount, Integer totalRequired, String coveredText,
            String currentText, String remainingText, UUID latestEvidenceId, boolean offerEndChoice,
            UUID completionProposalId, String completionRecap) {}
    record MissionSession(UUID id, String status, int revision, String participantName,
            List<String> remainingItems, List<String> followUpItems) {}
    private record Invitation(UUID id, UUID organisationId, UUID discoveryId, UUID missionId, UUID participantId) {}
    private record Session(UUID id, UUID participantId) {}
    private record SessionState(UUID id, String status, int revision, UUID organisationId,
            UUID discoveryId, UUID missionId) {}
    private record AnswerState(UUID id, String status, int revision, UUID organisationId,
            UUID discoveryId, UUID missionId, UUID participantId, UUID activeQuestionId) {}
    private record AcceptedAnswer(String answer, String participationSignal) {}
    private record ParticipantState(UUID id, String status, int revision, UUID organisationId,
            UUID discoveryId, UUID missionId, UUID participantId, UUID activeQuestionId,
            UUID currentCompletionProposalId) {}
    private record EvidenceState(UUID questionId, UUID itemId, String answer) {}
    private record OwnerSession(UUID id, String status, int revision, UUID organisationId,
            UUID currentCompletionProposalId) {}
}
