package com.findworks.interview;

import com.findworks.security.PilotTenant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class InterviewRepository {

    private final JdbcClient jdbc;
    private final PilotTenant tenant;

    InterviewRepository(JdbcClient jdbc, PilotTenant tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Transactional(readOnly = true)
    Mission mission(UUID id, String email) {
        var investigator = tenant.investigator(email);
        var mission = jdbc.sql("""
                SELECT m.id, d.title, d.objective, m.interviewee_name, m.interviewee_email, m.status,
                       COALESCE(s.status, 'not_invited') session_status
                FROM interview_missions m
                JOIN discoveries d ON d.id = m.discovery_id
                LEFT JOIN interview_sessions s ON s.interview_mission_id = m.id
                WHERE m.id = ? AND d.owner_membership_id = ?
                """).params(id, investigator.membershipId()).query((rs, row) -> new Mission(
                        rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("objective"),
                        rs.getString("interviewee_name"), rs.getString("interviewee_email"),
                        rs.getString("status"), rs.getString("session_status"), List.of())).optional()
                .orElseThrow(() -> new AccessDeniedException("Mission access denied."));
        var items = jdbc.sql("SELECT knowledge_gap, opening_question FROM investigation_items WHERE interview_mission_id = ? ORDER BY position")
                .param(id).query((rs, row) -> new Item(rs.getString(1), rs.getString(2))).list();
        return new Mission(mission.id(), mission.title(), mission.objective(), mission.intervieweeName(),
                mission.intervieweeEmail(), mission.status(), mission.sessionStatus(), items);
    }

    @Transactional
    String approveAndInvite(UUID missionId, String email) {
        var investigator = tenant.investigator(email);
        var approved = jdbc.sql("""
                UPDATE interview_missions SET status = 'approved', approved_at = now()
                WHERE id = ? AND EXISTS (
                    SELECT 1 FROM discoveries d
                    WHERE d.id = interview_missions.discovery_id AND d.owner_membership_id = ?)
                """).params(missionId, investigator.membershipId()).update();
        if (approved == 0) {
            throw new AccessDeniedException("Mission access denied.");
        }
        jdbc.sql("UPDATE invitations SET revoked_at = now() WHERE interview_mission_id = ? AND revoked_at IS NULL")
                .param(missionId).update();
        jdbc.sql("UPDATE interview_access_grants SET revoked_at = now() WHERE interview_session_id IN (SELECT id FROM interview_sessions WHERE interview_mission_id = ?) AND revoked_at IS NULL")
                .param(missionId).update();
        var token = UUID.randomUUID() + "" + UUID.randomUUID();
        jdbc.sql("INSERT INTO invitations (id, interview_mission_id, token_hash, expires_at) VALUES (?, ?, ?, now() + interval '7 days')")
                .params(UUID.randomUUID(), missionId, hash(token)).update();
        return token;
    }

    @Transactional
    RedeemedInvitation redeem(String invitationToken) {
        tenant.select();
        var invitation = jdbc.sql("""
                SELECT i.id, i.interview_mission_id
                FROM invitations i
                WHERE i.token_hash = ? AND i.revoked_at IS NULL AND i.redeemed_at IS NULL AND i.expires_at > now()
                FOR UPDATE
                """).param(hash(invitationToken)).query((rs, row) -> new Invitation(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional()
                .orElseThrow(() -> new IllegalArgumentException("This invitation is invalid, expired, or already used."));
        jdbc.sql("UPDATE invitations SET redeemed_at = now() WHERE id = ?").param(invitation.id()).update();
        var sessionId = jdbc.sql("SELECT id FROM interview_sessions WHERE interview_mission_id = ?")
                .param(invitation.missionId()).query(UUID.class).optional().orElseGet(() -> {
                    var id = UUID.randomUUID();
                    jdbc.sql("INSERT INTO interview_sessions (id, interview_mission_id) VALUES (?, ?)")
                            .params(id, invitation.missionId()).update();
                    return id;
                });
        var accessToken = UUID.randomUUID() + "" + UUID.randomUUID();
        jdbc.sql("INSERT INTO interview_access_grants (id, interview_session_id, token_hash, expires_at) VALUES (?, ?, ?, now() + interval '7 days')")
                .params(UUID.randomUUID(), sessionId, hash(accessToken)).update();
        return new RedeemedInvitation(accessToken);
    }

    @Transactional(readOnly = true)
    Interview interview(String accessToken) {
        tenant.select();
        return jdbc.sql("""
                SELECT s.id, s.status, s.current_position, d.title, d.objective, m.interviewee_name,
                       i.id item_id, i.opening_question,
                       (SELECT count(*) FROM investigation_items x WHERE x.interview_mission_id = m.id) total
                FROM interview_access_grants g
                JOIN interview_sessions s ON s.id = g.interview_session_id
                JOIN interview_missions m ON m.id = s.interview_mission_id
                JOIN discoveries d ON d.id = m.discovery_id
                LEFT JOIN investigation_items i ON i.interview_mission_id = m.id AND i.position = s.current_position
                WHERE g.token_hash = ? AND g.revoked_at IS NULL AND g.expires_at > now()
                """).param(hash(accessToken)).query((rs, row) -> new Interview(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getInt("current_position"),
                        rs.getInt("total"), rs.getString("title"), rs.getString("objective"),
                        rs.getString("interviewee_name"), rs.getObject("item_id", UUID.class),
                        rs.getString("opening_question"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("Your interview access has expired."));
    }

    @Transactional
    void answer(String accessToken, UUID itemId, String answer) {
        if (answer == null || answer.isBlank() || answer.length() > 10_000) {
            throw new IllegalArgumentException("Please give an answer before continuing.");
        }
        var interview = interview(accessToken);
        if (!itemId.equals(interview.itemId()) || "completed".equals(interview.status())) {
            throw new IllegalArgumentException("That question is no longer active.");
        }
        jdbc.sql("INSERT INTO evidence (id, interview_session_id, investigation_item_id, answer) VALUES (?, ?, ?, ?)")
                .params(UUID.randomUUID(), interview.sessionId(), itemId, answer.trim()).update();
        var complete = interview.position() + 1 >= interview.total();
        jdbc.sql("""
                UPDATE interview_sessions
                SET current_position = current_position + 1,
                    status = ?, started_at = COALESCE(started_at, now()), completed_at = CASE WHEN ? THEN now() ELSE NULL END
                WHERE id = ?
                """).params(complete ? "completed" : "in_progress", complete, interview.sessionId()).update();
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
                """).params(missionId, investigator.membershipId()).query((rs, row) -> {
                    var answeredAt = rs.getTimestamp("created_at");
                    return new Finding(rs.getString("knowledge_gap"), rs.getString("answer"),
                            answeredAt == null ? null : answeredAt.toInstant());
                }).list();
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    record Mission(UUID id, String title, String objective, String intervieweeName, String intervieweeEmail,
                   String status, String sessionStatus, List<Item> items) {}
    record Item(String knowledgeGap, String question) {}
    record Invitation(UUID id, UUID missionId) {}
    record RedeemedInvitation(String accessToken) {}
    record Interview(UUID sessionId, String status, int position, int total, String title, String objective,
                     String intervieweeName, UUID itemId, String question) {}
    record Finding(String knowledgeGap, String answer, Instant answeredAt) {}
}
