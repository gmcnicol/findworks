package com.findworks.interview;

import com.findworks.security.PilotTenant;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class InvitationRepository {

    private final JdbcClient jdbc;
    private final PilotTenant tenant;
    private final Clock clock;
    private final InvitationToken tokens;
    private final InvitationProperties properties;

    InvitationRepository(JdbcClient jdbc, PilotTenant tenant, Clock clock, InvitationToken tokens,
            InvitationProperties properties) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.clock = clock;
        this.tokens = tokens;
        this.properties = properties;
    }

    @Transactional
    UUID send(UUID missionId, String recipientEmail, boolean confirmed, String email) {
        if (!confirmed) {
            throw new IllegalArgumentException("Confirm the exact recipient and approved Mission version.");
        }
        var recipient = recipient(recipientEmail);
        var investigator = tenant.investigator(email);
        var mission = approvedMission(missionId, investigator.membershipId());
        properties.origin();
        properties.requiredSender();
        var keyId = tokens.activeKeyId();
        var participantId = participant(mission, recipient);
        var current = jdbc.sql("""
                SELECT id, participant_id, delivery_status FROM invitations
                WHERE interview_mission_id = ? AND revoked_at IS NULL
                FOR UPDATE
                """).param(missionId).query((rs, ignored) -> new Current(
                        rs.getObject("id", UUID.class), rs.getObject("participant_id", UUID.class),
                        rs.getString("delivery_status"))).optional();
        if (current.isPresent()) {
            if ("legacy_inert".equals(current.get().status())) {
                throw new IllegalArgumentException("This legacy Invitation must be explicitly reissued.");
            }
            if (!participantId.equals(current.get().participantId())) {
                throw new IllegalArgumentException("This Mission version already has another current recipient.");
            }
            return current.get().id();
        }
        var invitationId = create(mission, participantId, recipient, keyId);
        tenant.audit(investigator, "invitation_send_confirmed", "invitation", invitationId);
        return invitationId;
    }

    @Transactional(readOnly = true)
    View forMission(UUID missionId, String email) {
        var investigator = tenant.investigator(email);
        return jdbc.sql("""
                SELECT i.id, i.recipient_email,
                       CASE WHEN i.expires_at <= ? AND i.delivery_status NOT IN ('redeemed', 'revoked')
                            THEN 'expired' ELSE i.delivery_status END AS delivery_status,
                       i.expires_at,
                       j.delivery_cycle, j.attempt_count
                FROM invitations i
                JOIN interview_missions m ON m.id = i.interview_mission_id
                JOIN discoveries d ON d.id = m.discovery_id
                LEFT JOIN invitation_delivery_jobs j ON j.invitation_id = i.id
                WHERE i.interview_mission_id = ? AND d.owner_membership_id = ?
                ORDER BY i.created_at DESC LIMIT 1
                """).params(timestamp(clock.instant()), missionId, investigator.membershipId()).query((rs, ignored) -> new View(
                        rs.getObject("id", UUID.class), rs.getString("recipient_email"),
                        rs.getString("delivery_status"), rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("delivery_cycle", Integer.class),
                        rs.getObject("attempt_count", Integer.class))).optional().orElse(null);
    }

    @Transactional
    void retry(UUID missionId, UUID invitationId, String email) {
        var investigator = tenant.investigator(email);
        approvedMission(missionId, investigator.membershipId());
        var updated = jdbc.sql("""
                UPDATE invitation_delivery_jobs j
                SET delivery_cycle = delivery_cycle + 1, attempt_count = 0, status = 'pending',
                    next_attempt_at = ?, lease_owner = NULL, lease_expires_at = NULL, updated_at = ?
                FROM invitations i
                WHERE j.invitation_id = i.id AND i.id = ? AND i.interview_mission_id = ?
                  AND i.delivery_status = 'failed' AND i.revoked_at IS NULL AND j.status = 'failed'
                """).params(timestamp(clock.instant()), timestamp(clock.instant()), invitationId, missionId).update();
        if (updated == 0) {
            throw new IllegalArgumentException("Only an exhausted current Invitation can be retried.");
        }
        jdbc.sql("""
                UPDATE invitations SET delivery_status = 'pending', provider_message_id = NULL
                WHERE id = ?
                """).param(invitationId).update();
        tenant.audit(investigator, "invitation_delivery_retried", "invitation", invitationId);
    }

    @Transactional
    UUID reissue(UUID missionId, UUID invitationId, String email) {
        var investigator = tenant.investigator(email);
        var mission = approvedMission(missionId, investigator.membershipId());
        var old = jdbc.sql("""
                SELECT participant_id, recipient_email FROM invitations
                WHERE id = ? AND interview_mission_id = ? AND revoked_at IS NULL
                FOR UPDATE
                """).params(invitationId, missionId).query((rs, ignored) -> new Recipient(
                        rs.getObject("participant_id", UUID.class), rs.getString("recipient_email"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("Only a current Invitation can be reissued."));
        properties.origin();
        properties.requiredSender();
        revoke(missionId, old.participantId());
        var replacement = create(mission, old.participantId(), old.email(), tokens.activeKeyId());
        tenant.audit(investigator, "invitation_reissued", "invitation", replacement);
        return replacement;
    }

    @Transactional
    void revokeBeforeSessionStart(UUID missionId) {
        var participantIds = jdbc.sql("""
                SELECT participant_id FROM invitations i
                WHERE i.interview_mission_id = ? AND i.revoked_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM interview_sessions s
                      WHERE s.interview_mission_id = i.interview_mission_id
                        AND (s.status <> 'not_started' OR s.started_at IS NOT NULL))
                """).param(missionId).query(UUID.class).list();
        participantIds.forEach(participantId -> revoke(missionId, participantId));
    }

    @Transactional
    Work claimNext() {
        tenant.select();
        var now = clock.instant();
        var leaseOwner = UUID.randomUUID();
        var row = jdbc.sql("""
                SELECT j.id, j.organisation_id, j.invitation_id, j.delivery_cycle, j.attempt_count,
                       i.recipient_email, i.token_key_id, m.interviewee_name
                FROM invitation_delivery_jobs j
                JOIN invitations i ON i.id = j.invitation_id
                JOIN interview_missions m ON m.id = i.interview_mission_id
                WHERE i.delivery_status = 'pending' AND i.revoked_at IS NULL
                  AND j.attempt_count < 3 AND j.next_attempt_at <= ?
                  AND (j.status = 'pending' OR (j.status = 'leased' AND j.lease_expires_at <= ?))
                ORDER BY j.next_attempt_at, j.created_at
                FOR UPDATE OF j SKIP LOCKED LIMIT 1
                """).params(timestamp(now), timestamp(now)).query((rs, ignored) -> new Work(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("invitation_id", UUID.class), rs.getInt("delivery_cycle"),
                        rs.getInt("attempt_count") + 1, rs.getString("recipient_email"),
                        rs.getString("token_key_id"), rs.getString("interviewee_name"),
                        leaseOwner)).optional().orElse(null);
        if (row == null) {
            return null;
        }
        jdbc.sql("""
                UPDATE invitation_delivery_jobs
                SET status = 'leased', attempt_count = ?, lease_owner = ?, lease_expires_at = ?, updated_at = ?
                WHERE id = ?
                """).params(row.attemptNumber(), leaseOwner, timestamp(now.plus(Duration.ofMinutes(5))),
                timestamp(now), row.jobId()).update();
        return row;
    }

    @Transactional
    void accepted(Work work, String providerMessageId) {
        if (providerMessageId == null || providerMessageId.isBlank() || providerMessageId.length() > 500) {
            failed(work, "provider_rejected");
            return;
        }
        tenant.select();
        if (!settleJob(work, "accepted", null)) {
            return;
        }
        attempt(work, "provider_accepted", null, providerMessageId);
        jdbc.sql("""
                UPDATE invitations SET delivery_status = 'provider_accepted', provider_message_id = ?
                WHERE id = ? AND delivery_status = 'pending' AND revoked_at IS NULL
                """).params(providerMessageId, work.invitationId()).update();
        tenant.auditSystem("invitation_provider_accepted", "invitation", work.invitationId());
    }

    @Transactional
    void failed(Work work, String errorClass) {
        if (!java.util.Set.of("provider_unconfigured", "provider_rejected", "provider_unavailable")
                .contains(errorClass)) {
            errorClass = "provider_unavailable";
        }
        tenant.select();
        var exhausted = work.attemptNumber() >= 3;
        if (!settleJob(work, exhausted ? "failed" : "pending", exhausted ? null : clock.instant())) {
            return;
        }
        attempt(work, "failed", errorClass, null);
        if (exhausted) {
            jdbc.sql("""
                    UPDATE invitations SET delivery_status = 'failed'
                    WHERE id = ? AND delivery_status = 'pending' AND revoked_at IS NULL
                    """).param(work.invitationId()).update();
            tenant.auditSystem("invitation_delivery_exhausted", "invitation", work.invitationId());
        }
    }

    private boolean settleJob(Work work, String status, Instant nextAttempt) {
        return jdbc.sql("""
                UPDATE invitation_delivery_jobs
                SET status = ?, next_attempt_at = COALESCE(?, next_attempt_at),
                    lease_owner = NULL, lease_expires_at = NULL, updated_at = ?
                WHERE id = ? AND delivery_cycle = ? AND attempt_count = ?
                  AND status = 'leased' AND lease_owner = ?
                """).params(status, nextAttempt == null ? null : timestamp(nextAttempt), timestamp(clock.instant()),
                work.jobId(), work.deliveryCycle(),
                work.attemptNumber(), work.leaseOwner()).update() == 1;
    }

    private void attempt(Work work, String outcome, String errorClass, String providerMessageId) {
        jdbc.sql("""
                INSERT INTO invitation_delivery_attempts (
                    id, organisation_id, invitation_id, delivery_cycle, attempt_number,
                    outcome, error_class, provider_message_id, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(UUID.randomUUID(), work.organisationId(), work.invitationId(), work.deliveryCycle(),
                work.attemptNumber(), outcome, errorClass, providerMessageId, timestamp(clock.instant())).update();
    }

    private Mission approvedMission(UUID missionId, UUID membershipId) {
        return jdbc.sql("""
                SELECT m.id, m.organisation_id, m.discovery_id, m.interviewee_name
                FROM interview_missions m
                JOIN discoveries d ON d.id = m.discovery_id
                WHERE m.id = ? AND d.owner_membership_id = ? AND d.status = 'active'
                  AND m.status = 'approved'
                  AND NOT EXISTS (SELECT 1 FROM interview_missions newer
                                  WHERE newer.lineage_id = m.lineage_id AND newer.version > m.version)
                  AND length(trim(m.objective)) > 0
                  AND length(trim(m.desired_outcome)) > 0
                  AND length(trim(m.interviewee_name)) > 0
                  AND length(trim(m.interviewee_relevance)) > 0
                  AND length(trim(m.completion_criteria)) > 0
                  AND length(trim(m.expected_commitment)) > 0
                  AND length(trim(m.data_use_summary)) > 0
                  AND EXISTS (SELECT 1 FROM mission_contexts c
                              WHERE c.interview_mission_id = m.id AND c.visibility = 'shared')
                  AND EXISTS (SELECT 1 FROM mission_boundaries b
                              WHERE b.interview_mission_id = m.id AND b.boundary_kind = 'boundary')
                  AND EXISTS (SELECT 1 FROM mission_boundaries b
                              WHERE b.interview_mission_id = m.id AND b.boundary_kind = 'prohibited_topic')
                  AND EXISTS (SELECT 1 FROM mission_opening_questions q WHERE q.interview_mission_id = m.id)
                  AND EXISTS (SELECT 1 FROM investigation_items item
                              WHERE item.interview_mission_id = m.id AND item.required)
                  AND NOT EXISTS (SELECT 1 FROM mission_ambiguities a
                                  WHERE a.interview_mission_id = m.id AND a.represented_by_item_id IS NULL)
                FOR UPDATE OF m
                """).params(missionId, membershipId).query((rs, ignored) -> new Mission(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("discovery_id", UUID.class), rs.getString("interviewee_name"))).optional()
                .orElseThrow(() -> new AccessDeniedException("Approved Mission version access denied."));
    }

    private UUID participant(Mission mission, String recipient) {
        var existing = jdbc.sql("""
                SELECT id FROM discovery_participants
                WHERE discovery_id = ? AND lower(email) = lower(?)
                """).params(mission.discoveryId(), recipient).query(UUID.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO discovery_participants (
                    id, organisation_id, discovery_id, intended_name, email
                ) VALUES (?, ?, ?, ?, ?)
                """).params(id, mission.organisationId(), mission.discoveryId(), mission.intervieweeName(), recipient)
                .update();
        return id;
    }

    private UUID create(Mission mission, UUID participantId, String recipient, String keyId) {
        var id = UUID.randomUUID();
        var confirmedAt = clock.instant();
        var rawToken = tokens.token(id, keyId);
        jdbc.sql("""
                INSERT INTO invitations (
                    id, organisation_id, discovery_id, interview_mission_id, participant_id, recipient_email,
                    token_key_id, token_hash, send_confirmed_at, delivery_status, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?)
                """).params(id, mission.organisationId(), mission.discoveryId(), mission.id(), participantId,
                recipient, keyId,
                tokens.hash(rawToken), timestamp(confirmedAt), timestamp(confirmedAt.plus(Duration.ofDays(7)))).update();
        jdbc.sql("""
                INSERT INTO invitation_delivery_jobs (
                    id, organisation_id, invitation_id, next_attempt_at
                ) VALUES (?, ?, ?, ?)
                """).params(UUID.randomUUID(), mission.organisationId(), id, timestamp(confirmedAt)).update();
        return id;
    }

    private void revoke(UUID missionId, UUID participantId) {
        jdbc.sql("""
                UPDATE invitations SET delivery_status = 'revoked', revoked_at = COALESCE(revoked_at, ?)
                WHERE interview_mission_id = ? AND participant_id = ? AND revoked_at IS NULL
                """).params(timestamp(clock.instant()), missionId, participantId).update();
        jdbc.sql("""
                UPDATE invitation_delivery_jobs SET status = 'failed', lease_owner = NULL,
                    lease_expires_at = NULL, updated_at = ?
                WHERE invitation_id IN (
                    SELECT id FROM invitations WHERE interview_mission_id = ? AND participant_id = ?)
                  AND status IN ('pending', 'leased')
                """).params(timestamp(clock.instant()), missionId, participantId).update();
        jdbc.sql("""
                UPDATE interview_access_grants SET revoked_at = COALESCE(revoked_at, ?)
                WHERE interview_session_id IN (
                    SELECT id FROM interview_sessions WHERE interview_mission_id = ?)
                  AND revoked_at IS NULL
                """).params(timestamp(clock.instant()), missionId).update();
    }

    private static String recipient(String email) {
        var value = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (value.length() > 320 || !value.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("Enter a valid recipient email address.");
        }
        return value;
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    record View(UUID id, String recipientEmail, String status, Instant expiresAt,
            Integer deliveryCycle, Integer attemptCount) {
        public String label() {
            return switch (status) {
                case "pending" -> "Pending provider acceptance";
                case "provider_accepted" -> "Accepted by email provider";
                case "failed" -> "Delivery attempt failed";
                case "redeemed" -> "Invitation redeemed";
                case "revoked" -> "Invitation revoked";
                case "expired" -> "Invitation expired";
                default -> "Legacy Invitation requires reissue";
            };
        }

        public boolean current() {
            return !"revoked".equals(status);
        }
    }

    record Work(UUID jobId, UUID organisationId, UUID invitationId, int deliveryCycle,
            int attemptNumber, String recipientEmail, String tokenKeyId,
            String intervieweeName, UUID leaseOwner) {}
    private record Mission(UUID id, UUID organisationId, UUID discoveryId, String intervieweeName) {}
    private record Current(UUID id, UUID participantId, String status) {}
    private record Recipient(UUID participantId, String email) {}
}
