package com.findworks.shaping;

import com.findworks.security.PilotTenant;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ShapingRepository {

    private final JdbcClient jdbc;
    private final PilotTenant tenant;

    ShapingRepository(JdbcClient jdbc, PilotTenant tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Transactional
    public UUID submit(String email, UUID discoveryId, String content) {
        if (content == null || content.isBlank() || content.length() > 10_000) {
            throw new IllegalArgumentException("Add a statement of up to 10,000 characters.");
        }
        var investigator = tenant.investigator(email);
        var owned = jdbc.sql("""
                SELECT count(*) FROM discoveries
                WHERE id = ? AND owner_membership_id = ? AND status = 'active'
                """).params(discoveryId, investigator.membershipId()).query(Integer.class).single();
        if (owned == 0) {
            throw new AccessDeniedException("Discovery access denied.");
        }

        var proposedSessionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO discovery_shaping_sessions (id, organisation_id, discovery_id)
                VALUES (?, ?, ?) ON CONFLICT (discovery_id) DO NOTHING
                """).params(proposedSessionId, investigator.organisationId(), discoveryId).update();
        var sessionId = jdbc.sql("""
                SELECT id FROM discovery_shaping_sessions WHERE discovery_id = ? FOR UPDATE
                """).param(discoveryId).query(UUID.class).single();
        var activeWork = jdbc.sql("""
                SELECT count(*) FROM shaping_runtime_work
                WHERE shaping_session_id = ? AND status IN ('queued', 'running')
                """).param(sessionId).query(Integer.class).single();
        if (activeWork > 0) {
            throw new IllegalArgumentException("Wait for FindWorks to respond before adding another statement.");
        }

        var position = nextPosition(sessionId);
        var messageId = UUID.randomUUID();
        var workId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO discovery_shaping_messages
                    (id, organisation_id, shaping_session_id, position, author_kind, author_id, content)
                VALUES (?, ?, ?, ?, 'investigator', ?, ?)
                """).params(messageId, investigator.organisationId(), sessionId, position,
                investigator.membershipId(), content.trim()).update();
        jdbc.sql("""
                INSERT INTO shaping_runtime_work
                    (id, organisation_id, shaping_session_id, trigger_message_id)
                VALUES (?, ?, ?, ?)
                """).params(workId, investigator.organisationId(), sessionId, messageId).update();
        jdbc.sql("""
                UPDATE discovery_shaping_sessions SET updated_at = now() WHERE id = ?
                """).param(sessionId).update();
        jdbc.sql("""
                UPDATE discoveries SET last_activity_at = now(), updated_at = now() WHERE id = ?
                """).param(discoveryId).update();
        tenant.audit(investigator, "shaping_message_submitted", "discovery_shaping_session", sessionId);
        return workId;
    }

    @Transactional(readOnly = true)
    public ShapingView view(String email, UUID discoveryId) {
        var investigator = tenant.investigator(email);
        var owned = jdbc.sql("""
                SELECT count(*) FROM discoveries
                WHERE id = ? AND owner_membership_id = ? AND status = 'active'
                """).params(discoveryId, investigator.membershipId()).query(Integer.class).single();
        if (owned == 0) {
            throw new AccessDeniedException("Discovery access denied.");
        }
        var sessionId = jdbc.sql("SELECT id FROM discovery_shaping_sessions WHERE discovery_id = ?")
                .param(discoveryId).query(UUID.class).optional();
        if (sessionId.isEmpty()) {
            return ShapingView.empty();
        }
        var messages = messages(sessionId.get()).stream()
                .map(message -> new ShapingView.Message(message.authorKind(), message.content(), message.createdAt()))
                .toList();
        var latestWork = jdbc.sql("""
                SELECT status FROM shaping_runtime_work
                WHERE shaping_session_id = ? ORDER BY created_at DESC LIMIT 1
                """).param(sessionId.get()).query(String.class).optional().orElse("succeeded");
        var lastAgentMessage = jdbc.sql("""
                SELECT id, content FROM discovery_shaping_messages
                WHERE shaping_session_id = ? AND author_kind = 'agent'
                ORDER BY position DESC LIMIT 1
                """).param(sessionId.get()).query((rs, row) -> new ShapingView.Event(
                        "question_ready", "question:" + rs.getObject("id", UUID.class), rs.getString("content")))
                .optional().orElse(null);
        var status = switch (latestWork) {
            case "queued", "running" -> "working";
            case "failed" -> "runtime_failed";
            default -> "ready";
        };
        return new ShapingView(sessionId.get(), messages, status,
                "ready".equals(status) ? lastAgentMessage : null);
    }

    @Transactional
    public Work claimNext() {
        tenant.select();
        var id = jdbc.sql("""
                SELECT id FROM shaping_runtime_work
                WHERE attempts < 3 AND available_at <= now()
                  AND (status = 'queued' OR (status = 'running' AND lease_until < now()))
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED LIMIT 1
                """).query(UUID.class).optional();
        if (id.isEmpty()) {
            return null;
        }
        return jdbc.sql("""
                UPDATE shaping_runtime_work
                SET status = 'running', attempts = attempts + 1,
                    lease_until = now() + interval '2 minutes', updated_at = now(), error_code = NULL
                WHERE id = ?
                RETURNING id, organisation_id, shaping_session_id, trigger_message_id, attempts
                """).param(id.get()).query((rs, row) -> new Work(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("shaping_session_id", UUID.class),
                        rs.getObject("trigger_message_id", UUID.class), rs.getInt("attempts"))).single();
    }

    @Transactional(readOnly = true)
    public Context context(Work work) {
        tenant.select();
        var discovery = jdbc.sql("""
                SELECT d.title, d.objective
                FROM shaping_runtime_work w
                JOIN discovery_shaping_sessions s ON s.id = w.shaping_session_id
                JOIN discoveries d ON d.id = s.discovery_id
                WHERE w.id = ? AND w.shaping_session_id = ? AND w.organisation_id = ?
                """).params(work.id(), work.sessionId(), work.organisationId())
                .query((rs, row) -> new DiscoveryContext(rs.getString("title"), rs.getString("objective")))
                .optional().orElseThrow(() -> new IllegalStateException("Shaping runtime scope no longer exists."));
        var messages = jdbc.sql("""
                SELECT m.author_kind, m.content, m.created_at
                FROM discovery_shaping_messages m
                JOIN discovery_shaping_messages trigger_message
                  ON trigger_message.id = ? AND trigger_message.shaping_session_id = m.shaping_session_id
                WHERE m.shaping_session_id = ? AND m.position <= trigger_message.position
                ORDER BY m.position
                """).params(work.triggerMessageId(), work.sessionId())
                .query((rs, row) -> new StoredMessage(rs.getString("author_kind"), rs.getString("content"),
                        rs.getTimestamp("created_at").toInstant())).list();
        return new Context(work.id(), work.sessionId(), discovery.title(), discovery.objective(), messages);
    }

    @Transactional
    public void complete(Work work, String question) {
        if (question == null || question.isBlank() || question.length() > 2_000) {
            throw new IllegalArgumentException("Pi returned an invalid shaping question.");
        }
        tenant.select();
        var status = jdbc.sql("SELECT status FROM shaping_runtime_work WHERE id = ? FOR UPDATE")
                .param(work.id()).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("Shaping runtime work no longer exists."));
        if ("succeeded".equals(status)) {
            return;
        }
        var messageId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO discovery_shaping_messages
                    (id, organisation_id, shaping_session_id, position, author_kind, content)
                VALUES (?, ?, ?, ?, 'agent', ?)
                """).params(messageId, work.organisationId(), work.sessionId(), nextPosition(work.sessionId()),
                question.trim()).update();
        jdbc.sql("""
                UPDATE shaping_runtime_work
                SET status = 'succeeded', response_message_id = ?, lease_until = NULL, updated_at = now()
                WHERE id = ?
                """).params(messageId, work.id()).update();
        jdbc.sql("UPDATE discovery_shaping_sessions SET updated_at = now() WHERE id = ?")
                .param(work.sessionId()).update();
        tenant.auditSystem("shaping_follow_up_ready", "discovery_shaping_session", work.sessionId());
    }

    @Transactional
    public void fail(Work work) {
        tenant.select();
        jdbc.sql("""
                UPDATE shaping_runtime_work
                SET status = CASE WHEN attempts >= 3 THEN 'failed' ELSE 'queued' END,
                    available_at = now() + interval '1 second', lease_until = NULL,
                    updated_at = now(), error_code = 'runtime_error'
                WHERE id = ? AND status = 'running'
                """).param(work.id()).update();
        if (work.attempts() >= 3) {
            tenant.auditSystem("shaping_runtime_failed", "discovery_shaping_session", work.sessionId());
        }
    }

    private int nextPosition(UUID sessionId) {
        return jdbc.sql("""
                SELECT coalesce(max(position), -1) + 1
                FROM discovery_shaping_messages WHERE shaping_session_id = ?
                """).param(sessionId).query(Integer.class).single();
    }

    private List<StoredMessage> messages(UUID sessionId) {
        return jdbc.sql("""
                SELECT author_kind, content, created_at
                FROM discovery_shaping_messages WHERE shaping_session_id = ? ORDER BY position
                """).param(sessionId).query((rs, row) -> new StoredMessage(
                        rs.getString("author_kind"), rs.getString("content"),
                        rs.getTimestamp("created_at").toInstant())).list();
    }

    public record Work(UUID id, UUID organisationId, UUID sessionId, UUID triggerMessageId, int attempts) {}
    public record Context(UUID workId, UUID sessionId, String title, String objective, List<StoredMessage> messages) {}
    public record StoredMessage(String authorKind, String content, Instant createdAt) {}
    private record DiscoveryContext(String title, String objective) {}
}
