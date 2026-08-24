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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class InterviewRepository {

    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcClient jdbc;
    private final PilotTenant tenant;
    private final Clock clock;
    private final InterviewProperties properties;

    InterviewRepository(JdbcClient jdbc, PilotTenant tenant, Clock clock, InterviewProperties properties) {
        this.jdbc = jdbc;
        this.tenant = tenant;
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
                       s.revision, q.id question_id, q.question, q.human_context,
                       e.covered_count, e.total_required, e.covered_text, e.current_text, e.remaining_text,
                       latest_run.status runtime_status,
                       EXISTS (SELECT 1 FROM evidence accepted
                               WHERE accepted.interview_session_id = s.id
                                 AND accepted.source_type = 'interviewee_answer') answer_saved,
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
                LEFT JOIN LATERAL (
                    SELECT r.status, r.trigger FROM interview_runtime_runs r
                    WHERE r.interview_session_id = s.id ORDER BY r.created_at DESC LIMIT 1
                ) latest_run ON true
                WHERE g.token_hash = ? AND g.revoked_at IS NULL AND g.expires_at > ?
                  AND d.status = 'active' AND m.approved_at IS NOT NULL
                  AND (s.status <> 'not_started' OR m.status = 'approved')
                """).params(hash(accessToken), timestamp(clock.instant())).query((rs, ignored) -> {
                    var investigatorEmail = rs.getString("investigator_email");
                    var question = rs.getString("question");
                    var runtimeStatus = rs.getString("runtime_status");
                    var status = rs.getString("status");
                    var runtimeState = "not_started".equals(status) ? null : question != null ? "question_ready"
                            : "failed".equals(runtimeStatus) ? "runtime_failed" : "working";
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
                            rs.getString("remaining_text"));
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
                SET status = 'active', started_at = ?, revision = revision + 1
                WHERE id = ? AND status = 'not_started'
                """).params(timestamp(clock.instant()), session.id()).update();
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
    void auditDenied() {
        tenant.select();
        tenant.auditSystemDenied("interview_access_denied", "interview_access");
    }

    @Transactional(readOnly = true)
    List<Finding> findings(UUID missionId, String email) {
        var investigator = tenant.investigator(email);
        return jdbc.sql("""
                SELECT i.knowledge_gap, e.answer, e.created_at
                FROM investigation_items i
                JOIN interview_missions m ON m.id = i.interview_mission_id
                LEFT JOIN interview_sessions s ON s.interview_mission_id = i.interview_mission_id
                LEFT JOIN evidence e ON e.interview_session_id = s.id AND e.investigation_item_id = i.id
                JOIN discoveries d ON d.id = m.discovery_id
                WHERE i.interview_mission_id = ? AND d.owner_membership_id = ?
                ORDER BY i.position
                """).params(missionId, investigator.membershipId()).query((rs, ignored) -> {
                    var answeredAt = rs.getTimestamp("created_at");
                    return new Finding(rs.getString("knowledge_gap"), rs.getString("answer"),
                            answeredAt == null ? null : answeredAt.toInstant());
                }).list();
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
            String currentText, String remainingText) {}
    record Finding(String knowledgeGap, String answer, Instant answeredAt) {}
    private record Invitation(UUID id, UUID organisationId, UUID discoveryId, UUID missionId, UUID participantId) {}
    private record Session(UUID id, UUID participantId) {}
    private record SessionState(UUID id, String status, int revision, UUID organisationId,
            UUID discoveryId, UUID missionId) {}
    private record AnswerState(UUID id, String status, int revision, UUID organisationId,
            UUID discoveryId, UUID missionId, UUID participantId, UUID activeQuestionId) {}
    private record AcceptedAnswer(String answer, String participationSignal) {}
}
