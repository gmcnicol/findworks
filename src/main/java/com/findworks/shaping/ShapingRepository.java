package com.findworks.shaping;

import com.findworks.security.PilotTenant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ShapingRepository {

    private static final Set<String> PRIORITIES = Set.of("high", "medium", "low");
    private static final Set<String> OUTCOMES = Set.of(
            "supported_knowledge", "unknown", "conflict", "ownership_gap");

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

        var messageId = UUID.randomUUID();
        var workId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO discovery_shaping_messages
                    (id, organisation_id, shaping_session_id, position, author_kind, author_id, content)
                VALUES (?, ?, ?, ?, 'investigator', ?, ?)
                """).params(messageId, investigator.organisationId(), sessionId, nextPosition(sessionId),
                investigator.membershipId(), content.trim()).update();
        jdbc.sql("""
                INSERT INTO shaping_runtime_work
                    (id, organisation_id, shaping_session_id, trigger_message_id)
                VALUES (?, ?, ?, ?)
                """).params(workId, investigator.organisationId(), sessionId, messageId).update();
        jdbc.sql("UPDATE discovery_shaping_sessions SET updated_at = now() WHERE id = ?")
                .param(sessionId).update();
        jdbc.sql("UPDATE discoveries SET last_activity_at = now(), updated_at = now() WHERE id = ?")
                .param(discoveryId).update();
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
                SELECT status, response_message_id FROM shaping_runtime_work
                WHERE shaping_session_id = ? ORDER BY created_at DESC LIMIT 1
                """).param(sessionId.get()).query((rs, row) -> new LatestWork(
                        rs.getString("status"),
                        rs.getObject("response_message_id", UUID.class))).optional().orElse(null);
        var status = latestWork == null ? "ready" : switch (latestWork.status()) {
            case "queued", "running" -> "working";
            case "failed" -> "runtime_failed";
            default -> "ready";
        };
        var proposal = proposal(sessionId.get());
        ShapingView.Event event = null;
        if ("ready".equals(status) && latestWork != null) {
            if (latestWork.responseMessageId() != null) {
                event = jdbc.sql("SELECT content FROM discovery_shaping_messages WHERE id = ?")
                        .param(latestWork.responseMessageId()).query(String.class)
                        .optional().map(question -> new ShapingView.Event(
                                "question_ready", "question:" + latestWork.responseMessageId(), question))
                        .orElse(null);
            } else if (proposal != null) {
                event = new ShapingView.Event("mission_proposal_ready", "proposal:" + proposal.id(), null);
            }
        }
        return new ShapingView(sessionId.get(), messages, status, event, proposal);
    }

    @Transactional
    public Work claimNext() {
        tenant.select();
        var candidate = jdbc.sql("""
                SELECT w.id, w.organisation_id FROM shaping_runtime_work w
                JOIN discovery_shaping_sessions s ON s.id = w.shaping_session_id
                JOIN discoveries d ON d.id = s.discovery_id
                WHERE w.attempts < 3 AND w.available_at <= now() AND d.status = 'active'
                  AND (w.status = 'queued' OR (w.status = 'running' AND w.lease_until < now()))
                ORDER BY w.created_at
                FOR UPDATE OF w SKIP LOCKED LIMIT 1
                """).query((rs, ignored) -> new Candidate(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class))).optional();
        if (candidate.isEmpty()) {
            return null;
        }
        var leaseOwner = UUID.randomUUID();
        var executionCorrelation = UUID.randomUUID();
        var slot = jdbc.sql("""
                UPDATE pi_worker_slots SET organisation_id = ?, shaping_work_id = ?, runtime_run_id = NULL,
                    lease_owner = ?, lease_expires_at = now() + interval '2 minutes',
                    heartbeat_at = now(), updated_at = now()
                WHERE slot_number = (SELECT slot_number FROM pi_worker_slots
                    WHERE shaping_work_id = ? OR lease_expires_at IS NULL OR lease_expires_at < now()
                    ORDER BY CASE WHEN shaping_work_id = ? THEN 0 ELSE 1 END, slot_number
                    FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING slot_number
                """).params(candidate.get().organisationId(), candidate.get().id(), leaseOwner,
                        candidate.get().id(), candidate.get().id())
                .query(Integer.class).optional();
        if (slot.isEmpty()) {
            return null;
        }
        return jdbc.sql("""
                UPDATE shaping_runtime_work
                SET status = 'running', attempts = attempts + 1,
                    lease_owner = ?, lease_until = now() + interval '2 minutes', heartbeat_at = now(),
                    execution_correlation_id = ?, updated_at = now(), error_code = NULL
                WHERE id = ?
                RETURNING id, organisation_id, shaping_session_id, trigger_message_id, attempts, lease_owner,
                          origin_correlation_id, execution_correlation_id
                """).params(leaseOwner, executionCorrelation, candidate.get().id()).query((rs, row) -> new Work(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("shaping_session_id", UUID.class),
                        rs.getObject("trigger_message_id", UUID.class), rs.getInt("attempts"),
                        rs.getObject("lease_owner", UUID.class),
                        rs.getObject("origin_correlation_id", UUID.class),
                        rs.getObject("execution_correlation_id", UUID.class))).single();
    }

    @Transactional
    public boolean heartbeat(Work work) {
        tenant.select();
        var slot = jdbc.sql("""
                UPDATE pi_worker_slots SET heartbeat_at = now(),
                    lease_expires_at = now() + interval '2 minutes', updated_at = now()
                WHERE shaping_work_id = ? AND lease_owner = ? AND lease_expires_at >= now()
                """).params(work.id(), work.leaseOwner()).update();
        var run = jdbc.sql("""
                UPDATE shaping_runtime_work SET heartbeat_at = now(),
                    lease_until = now() + interval '2 minutes', updated_at = now()
                WHERE id = ? AND status = 'running' AND lease_owner = ? AND lease_until >= now()
                """).params(work.id(), work.leaseOwner()).update();
        if (slot != run) {
            throw new IllegalStateException("Shaping worker lease is inconsistent.");
        }
        return run == 1;
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
                  AND w.status = 'running' AND w.lease_owner = ? AND d.status = 'active'
                """).params(work.id(), work.sessionId(), work.organisationId(), work.leaseOwner())
                .query((rs, row) -> new DiscoveryContext(rs.getString("title"), rs.getString("objective")))
                .optional().orElseThrow(() -> new IllegalStateException("Shaping runtime scope no longer exists."));
        var messages = jdbc.sql("""
                SELECT m.id, m.author_kind, m.content, m.created_at
                FROM discovery_shaping_messages m
                JOIN discovery_shaping_messages trigger_message
                  ON trigger_message.id = ? AND trigger_message.shaping_session_id = m.shaping_session_id
                WHERE m.shaping_session_id = ? AND m.position <= trigger_message.position
                ORDER BY m.position
                """).params(work.triggerMessageId(), work.sessionId())
                .query((rs, row) -> new StoredMessage(rs.getObject("id", UUID.class),
                        rs.getString("author_kind"), rs.getString("content"),
                        rs.getTimestamp("created_at").toInstant())).list();
        return new Context(work.id(), work.sessionId(), discovery.title(), discovery.objective(), messages);
    }

    @Transactional
    public void complete(Work work, String question) {
        requireText(question, 2_000, "shaping question");
        tenant.select();
        if (settled(work)) {
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
                SET status = 'succeeded', response_message_id = ?, lease_until = NULL,
                    lease_owner = NULL, heartbeat_at = NULL, updated_at = now()
                WHERE id = ? AND lease_owner = ?
                """).params(messageId, work.id(), work.leaseOwner()).update();
        releaseSlot(work);
        jdbc.sql("UPDATE discovery_shaping_sessions SET updated_at = now() WHERE id = ?")
                .param(work.sessionId()).update();
        tenant.auditSystem("shaping_follow_up_ready", "discovery_shaping_session", work.sessionId());
    }

    @Transactional
    public void complete(Work work, MissionProposal proposal) {
        tenant.select();
        if (settled(work)) {
            return;
        }
        var investigatorMessages = jdbc.sql("""
                SELECT m.id
                FROM discovery_shaping_messages m
                JOIN discovery_shaping_messages trigger_message
                  ON trigger_message.id = ? AND trigger_message.shaping_session_id = m.shaping_session_id
                WHERE m.shaping_session_id = ? AND m.organisation_id = ?
                  AND m.author_kind = 'investigator' AND m.position <= trigger_message.position
                """).params(work.triggerMessageId(), work.sessionId(), work.organisationId())
                .query(UUID.class).set();
        validate(proposal, investigatorMessages);

        var proposalId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO interview_mission_proposals (
                    id, organisation_id, shaping_session_id, runtime_work_id, objective, desired_outcome,
                    intended_interviewee, interviewee_relevance, completion_criteria, expected_commitment,
                    data_use_summary
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """).params(proposalId, work.organisationId(), work.sessionId(), work.id(),
                proposal.objective().value().trim(), proposal.desiredOutcome().value().trim(),
                proposal.intendedInterviewee().value().trim(), proposal.intervieweeRelevance().value().trim(),
                proposal.completionCriteria().value().trim(), proposal.expectedCommitment().value().trim(),
                proposal.dataUseSummary().value().trim()).update();
        provenance(work, proposalId, "objective", proposalId, proposal.objective().source());
        provenance(work, proposalId, "desired_outcome", proposalId, proposal.desiredOutcome().source());
        provenance(work, proposalId, "intended_interviewee", proposalId, proposal.intendedInterviewee().source());
        provenance(work, proposalId, "interviewee_relevance", proposalId, proposal.intervieweeRelevance().source());
        provenance(work, proposalId, "completion_criteria", proposalId, proposal.completionCriteria().source());
        provenance(work, proposalId, "expected_commitment", proposalId, proposal.expectedCommitment().source());
        provenance(work, proposalId, "data_use_summary", proposalId, proposal.dataUseSummary().source());

        for (var position = 0; position < proposal.contexts().size(); position++) {
            var context = proposal.contexts().get(position);
            var id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO mission_proposal_contexts
                        (id, organisation_id, proposal_id, position, visibility, content)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(id, work.organisationId(), proposalId, position,
                    context.visibility(), context.content().trim()).update();
            provenance(work, proposalId, "context", id, context.source());
        }
        for (var position = 0; position < proposal.boundaries().size(); position++) {
            var boundary = proposal.boundaries().get(position);
            var id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO mission_proposal_boundaries
                        (id, organisation_id, proposal_id, position, boundary_kind, content)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(id, work.organisationId(), proposalId, position,
                    boundary.kind(), boundary.content().trim()).update();
            provenance(work, proposalId, "boundary", id, boundary.source());
        }
        for (var position = 0; position < proposal.terminology().size(); position++) {
            var term = proposal.terminology().get(position);
            var id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO mission_proposal_terms
                        (id, organisation_id, proposal_id, position, term, meaning)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(id, work.organisationId(), proposalId, position,
                    term.term().trim(), term.meaning().trim()).update();
            provenance(work, proposalId, "term", id, term.source());
        }
        for (var position = 0; position < proposal.openingQuestions().size(); position++) {
            var question = proposal.openingQuestions().get(position);
            var id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO mission_proposal_opening_questions
                        (id, organisation_id, proposal_id, position, question)
                    VALUES (?, ?, ?, ?, ?)
                    """).params(id, work.organisationId(), proposalId, position, question.question().trim()).update();
            provenance(work, proposalId, "opening_question", id, question.source());
        }

        var itemIds = new ArrayList<UUID>();
        for (var position = 0; position < proposal.investigationItems().size(); position++) {
            var item = proposal.investigationItems().get(position);
            var itemId = UUID.randomUUID();
            itemIds.add(itemId);
            jdbc.sql("""
                    INSERT INTO mission_proposal_investigation_items (
                        id, organisation_id, proposal_id, position, knowledge_gap, importance, priority,
                        relevant_context, required
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """).params(itemId, work.organisationId(), proposalId, position,
                    item.knowledgeGap().trim(), item.importance().trim(), item.priority(),
                    item.relevantContext().trim(), item.required()).update();
            provenance(work, proposalId, "investigation_item", itemId, item.source());
            for (var outcomePosition = 0; outcomePosition < item.allowedOutcomes().size(); outcomePosition++) {
                var outcome = item.allowedOutcomes().get(outcomePosition);
                var outcomeId = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO mission_proposal_allowed_outcomes (
                            id, organisation_id, proposal_id, investigation_item_id, position, outcome_kind
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """).params(outcomeId, work.organisationId(), proposalId, itemId,
                        outcomePosition, outcome.kind()).update();
                provenance(work, proposalId, "allowed_outcome", outcomeId, outcome.source());
            }
        }
        for (var position = 0; position < proposal.unresolvedAmbiguities().size(); position++) {
            var ambiguity = proposal.unresolvedAmbiguities().get(position);
            var id = UUID.randomUUID();
            var representedBy = ambiguity.representedByItemPosition() == null ? null
                    : itemIds.get(ambiguity.representedByItemPosition());
            jdbc.sql("""
                    INSERT INTO mission_proposal_ambiguities (
                        id, organisation_id, proposal_id, position, content, represented_by_item_id
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """).params(id, work.organisationId(), proposalId, position,
                    ambiguity.content().trim(), representedBy).update();
            provenance(work, proposalId, "ambiguity", id, ambiguity.source());
        }

        jdbc.sql("""
                UPDATE shaping_runtime_work
                SET status = 'succeeded', response_message_id = NULL, lease_until = NULL,
                    lease_owner = NULL, heartbeat_at = NULL, updated_at = now()
                WHERE id = ? AND lease_owner = ?
                """).params(work.id(), work.leaseOwner()).update();
        releaseSlot(work);
        jdbc.sql("UPDATE discovery_shaping_sessions SET updated_at = now() WHERE id = ?")
                .param(work.sessionId()).update();
        tenant.auditSystem("interview_mission_proposal_ready", "interview_mission_proposal", proposalId);
    }

    @Transactional
    public void fail(Work work) {
        tenant.select();
        jdbc.sql("""
                UPDATE shaping_runtime_work
                SET status = CASE WHEN attempts >= 3 THEN 'failed' ELSE 'queued' END,
                    available_at = now() + interval '1 second', lease_until = NULL,
                    lease_owner = NULL, heartbeat_at = NULL,
                    updated_at = now(), error_code = 'runtime_error'
                WHERE id = ? AND status = 'running' AND lease_owner = ?
                """).params(work.id(), work.leaseOwner()).update();
        releaseSlot(work);
        if (work.attempts() >= 3) {
            tenant.auditSystem("shaping_runtime_failed", "discovery_shaping_session", work.sessionId());
        }
    }

    private boolean settled(Work work) {
        var status = jdbc.sql("""
                SELECT status FROM shaping_runtime_work
                WHERE id = ? AND shaping_session_id = ? AND organisation_id = ?
                  AND (status = 'succeeded' OR lease_owner = ?) FOR UPDATE
                """).params(work.id(), work.sessionId(), work.organisationId(), work.leaseOwner())
                .query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("Shaping runtime work no longer exists."));
        if ("succeeded".equals(status)) {
            return true;
        }
        if (!"running".equals(status)) {
            throw new IllegalStateException("Shaping runtime work is not running.");
        }
        return false;
    }

    private void releaseSlot(Work work) {
        jdbc.sql("""
                UPDATE pi_worker_slots SET organisation_id = NULL, shaping_work_id = NULL,
                    runtime_run_id = NULL, lease_owner = NULL, lease_expires_at = NULL,
                    heartbeat_at = NULL, updated_at = now()
                WHERE shaping_work_id = ? AND lease_owner = ?
                """).params(work.id(), work.leaseOwner()).update();
    }

    private void validate(MissionProposal proposal, Set<UUID> investigatorMessages) {
        if (proposal == null) {
            throw new IllegalArgumentException("Pi returned no Interview Mission proposal.");
        }
        sourced(proposal.objective(), 4_000, "objective", investigatorMessages);
        sourced(proposal.desiredOutcome(), 4_000, "desired outcome", investigatorMessages);
        sourced(proposal.intendedInterviewee(), 1_000, "intended interviewee", investigatorMessages);
        sourced(proposal.intervieweeRelevance(), 4_000, "interviewee relevance", investigatorMessages);
        sourced(proposal.completionCriteria(), 4_000, "completion criteria", investigatorMessages);
        sourced(proposal.expectedCommitment(), 1_000, "expected commitment", investigatorMessages);
        sourced(proposal.dataUseSummary(), 4_000, "data use summary", investigatorMessages);

        requireList(proposal.contexts(), 1, 30, "context");
        if (proposal.contexts().stream().noneMatch(context -> "shared".equals(context.visibility()))) {
            throw new IllegalArgumentException("Interview Mission proposal needs shared context.");
        }
        for (var context : proposal.contexts()) {
            requireAllowed(context.visibility(), Set.of("shared", "private"), "context visibility");
            requireText(context.content(), 4_000, "context");
            source(context.source(), investigatorMessages, "context");
        }
        requireList(proposal.boundaries(), 1, 30, "boundary");
        if (proposal.boundaries().stream().noneMatch(boundary -> "boundary".equals(boundary.kind()))) {
            throw new IllegalArgumentException("Interview Mission proposal needs a boundary.");
        }
        if (proposal.boundaries().stream().noneMatch(boundary -> "prohibited_topic".equals(boundary.kind()))) {
            throw new IllegalArgumentException("Interview Mission proposal needs explicit prohibited topics.");
        }
        for (var boundary : proposal.boundaries()) {
            requireAllowed(boundary.kind(), Set.of("boundary", "prohibited_topic"), "boundary kind");
            requireText(boundary.content(), 4_000, "boundary");
            source(boundary.source(), investigatorMessages, "boundary");
        }
        requireList(proposal.terminology(), 0, 30, "terminology");
        for (var term : proposal.terminology()) {
            requireText(term.term(), 300, "term");
            requireText(term.meaning(), 2_000, "term meaning");
            source(term.source(), investigatorMessages, "term");
        }
        requireList(proposal.openingQuestions(), 1, 20, "opening question");
        for (var question : proposal.openingQuestions()) {
            requireText(question.question(), 2_000, "opening question");
            source(question.source(), investigatorMessages, "opening question");
        }
        requireList(proposal.investigationItems(), 1, 50, "Investigation Item");
        for (var item : proposal.investigationItems()) {
            requireText(item.knowledgeGap(), 4_000, "Investigation Item knowledge gap");
            requireText(item.importance(), 4_000, "Investigation Item importance");
            requireAllowed(item.priority(), PRIORITIES, "Investigation Item priority");
            requireText(item.relevantContext(), 4_000, "Investigation Item context");
            source(item.source(), investigatorMessages, "Investigation Item");
            requireList(item.allowedOutcomes(), 1, 4, "allowed outcome");
            var seen = new HashSet<String>();
            for (var outcome : item.allowedOutcomes()) {
                requireAllowed(outcome.kind(), OUTCOMES, "allowed outcome");
                if (!seen.add(outcome.kind())) {
                    throw new IllegalArgumentException("Interview Mission proposal repeats an allowed outcome.");
                }
                source(outcome.source(), investigatorMessages, "allowed outcome");
            }
        }
        requireList(proposal.unresolvedAmbiguities(), 0, 30, "unresolved ambiguity");
        for (var ambiguity : proposal.unresolvedAmbiguities()) {
            requireText(ambiguity.content(), 4_000, "unresolved ambiguity");
            if (ambiguity.representedByItemPosition() != null
                    && (ambiguity.representedByItemPosition() < 0
                    || ambiguity.representedByItemPosition() >= proposal.investigationItems().size())) {
                throw new IllegalArgumentException("Unresolved ambiguity references an unknown Investigation Item.");
            }
            source(ambiguity.source(), investigatorMessages, "unresolved ambiguity");
        }
    }

    private void sourced(MissionProposal.SourcedText text, int max, String name, Set<UUID> investigatorMessages) {
        if (text == null) {
            throw new IllegalArgumentException("Interview Mission proposal is missing " + name + ".");
        }
        requireText(text.value(), max, name);
        source(text.source(), investigatorMessages, name);
    }

    private void source(MissionProposal.Source source, Set<UUID> investigatorMessages, String name) {
        if (source == null || source.kind() == null || source.messageIds() == null) {
            throw new IllegalArgumentException("Interview Mission proposal is missing " + name + " provenance.");
        }
        if ("agent_proposal".equals(source.kind())) {
            if (!source.messageIds().isEmpty()) {
                throw new IllegalArgumentException("Agent proposal provenance cannot cite messages.");
            }
            return;
        }
        if (!"investigator_message".equals(source.kind()) || source.messageIds().isEmpty()
                || source.messageIds().size() > 50
                || new HashSet<>(source.messageIds()).size() != source.messageIds().size()
                || !investigatorMessages.containsAll(source.messageIds())) {
            throw new IllegalArgumentException("Interview Mission proposal cites invalid Investigator provenance.");
        }
    }

    private void provenance(Work work, UUID proposalId, String elementKind, UUID elementId,
            MissionProposal.Source source) {
        if ("agent_proposal".equals(source.kind())) {
            jdbc.sql("""
                    INSERT INTO mission_proposal_provenance (
                        id, organisation_id, proposal_id, shaping_session_id, element_kind, element_id,
                        source_kind, source_runtime_work_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 'agent_proposal', ?)
                    """).params(UUID.randomUUID(), work.organisationId(), proposalId, work.sessionId(),
                    elementKind, elementId, work.id()).update();
            return;
        }
        for (var messageId : source.messageIds()) {
            jdbc.sql("""
                    INSERT INTO mission_proposal_provenance (
                        id, organisation_id, proposal_id, shaping_session_id, element_kind, element_id,
                        source_kind, source_message_id
                    ) VALUES (?, ?, ?, ?, ?, ?, 'investigator_message', ?)
                    """).params(UUID.randomUUID(), work.organisationId(), proposalId, work.sessionId(),
                    elementKind, elementId, messageId).update();
        }
    }

    private ShapingView.Proposal proposal(UUID sessionId) {
        var row = jdbc.sql("""
                SELECT id, status, objective, desired_outcome, intended_interviewee, interviewee_relevance,
                       completion_criteria, expected_commitment, data_use_summary
                FROM interview_mission_proposals
                WHERE shaping_session_id = ? ORDER BY created_at DESC LIMIT 1
                """).param(sessionId).query((rs, ignored) -> new ProposalRow(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getString("objective"),
                        rs.getString("desired_outcome"), rs.getString("intended_interviewee"),
                        rs.getString("interviewee_relevance"), rs.getString("completion_criteria"),
                        rs.getString("expected_commitment"), rs.getString("data_use_summary")))
                .optional().orElse(null);
        if (row == null) {
            return null;
        }
        var provenance = jdbc.sql("""
                SELECT element_kind, element_id,
                       CASE WHEN bool_or(source_kind = 'agent_proposal')
                            THEN 'Agent proposal awaiting confirmation'
                            ELSE 'Linked to ' || count(source_message_id) || ' Investigator statement'
                                 || CASE WHEN count(source_message_id) = 1 THEN '' ELSE 's' END
                       END AS summary
                FROM mission_proposal_provenance WHERE proposal_id = ?
                GROUP BY element_kind, element_id
                """).param(row.id()).query((rs, ignored) -> new ProvenanceRow(
                        rs.getString("element_kind"), rs.getObject("element_id", UUID.class),
                        rs.getString("summary"))).list().stream().collect(java.util.stream.Collectors.toMap(
                                value -> new ElementKey(value.kind(), value.id()), ProvenanceRow::summary));
        var contexts = jdbc.sql("""
                SELECT id, visibility, content FROM mission_proposal_contexts
                WHERE proposal_id = ? ORDER BY position
                """).param(row.id()).query((rs, ignored) -> new ShapingView.Context(
                        rs.getString("visibility"), rs.getString("content"), provenance.get(
                                new ElementKey("context", rs.getObject("id", UUID.class))))).list();
        var boundaries = jdbc.sql("""
                SELECT id, boundary_kind, content FROM mission_proposal_boundaries
                WHERE proposal_id = ? ORDER BY position
                """).param(row.id()).query((rs, ignored) -> new ShapingView.Boundary(
                        rs.getString("boundary_kind"), rs.getString("content"), provenance.get(
                                new ElementKey("boundary", rs.getObject("id", UUID.class))))).list();
        var terms = jdbc.sql("""
                SELECT id, term, meaning FROM mission_proposal_terms
                WHERE proposal_id = ? ORDER BY position
                """).param(row.id()).query((rs, ignored) -> new ShapingView.Term(
                        rs.getString("term"), rs.getString("meaning"), provenance.get(
                                new ElementKey("term", rs.getObject("id", UUID.class))))).list();
        var questions = jdbc.sql("""
                SELECT id, question FROM mission_proposal_opening_questions
                WHERE proposal_id = ? ORDER BY position
                """).param(row.id()).query((rs, ignored) -> new ShapingView.OpeningQuestion(
                        rs.getString("question"), provenance.get(new ElementKey(
                                "opening_question", rs.getObject("id", UUID.class))))).list();
        var items = jdbc.sql("""
                SELECT id, knowledge_gap, importance, priority, relevant_context, required
                FROM mission_proposal_investigation_items WHERE proposal_id = ? ORDER BY position
                """).param(row.id()).query((rs, ignored) -> {
                    var itemId = rs.getObject("id", UUID.class);
                    var outcomes = jdbc.sql("""
                            SELECT id, outcome_kind FROM mission_proposal_allowed_outcomes
                            WHERE investigation_item_id = ? ORDER BY position
                            """).param(itemId).query((outcome, index) -> new ShapingView.Outcome(
                                    outcome.getString("outcome_kind"), provenance.get(new ElementKey(
                                            "allowed_outcome", outcome.getObject("id", UUID.class))))).list();
                    return new ShapingView.InvestigationItem(
                            rs.getString("knowledge_gap"), rs.getString("importance"), rs.getString("priority"),
                            rs.getString("relevant_context"), rs.getBoolean("required"), outcomes,
                            provenance.get(new ElementKey("investigation_item", itemId)));
                }).list();
        var ambiguities = jdbc.sql("""
                SELECT a.id, a.content, i.position AS item_position
                FROM mission_proposal_ambiguities a
                LEFT JOIN mission_proposal_investigation_items i ON i.id = a.represented_by_item_id
                WHERE a.proposal_id = ? ORDER BY a.position
                """).param(row.id()).query((rs, ignored) -> new ShapingView.Ambiguity(
                        rs.getString("content"), rs.getObject("item_position", Integer.class), provenance.get(
                                new ElementKey("ambiguity", rs.getObject("id", UUID.class))))).list();
        return new ShapingView.Proposal(
                row.id(), row.status(), text(row.objective(), "objective", row.id(), provenance),
                text(row.desiredOutcome(), "desired_outcome", row.id(), provenance),
                text(row.intendedInterviewee(), "intended_interviewee", row.id(), provenance),
                text(row.intervieweeRelevance(), "interviewee_relevance", row.id(), provenance),
                contexts, boundaries, terms, questions,
                text(row.completionCriteria(), "completion_criteria", row.id(), provenance),
                text(row.expectedCommitment(), "expected_commitment", row.id(), provenance),
                text(row.dataUseSummary(), "data_use_summary", row.id(), provenance),
                items, ambiguities);
    }

    private ShapingView.Text text(String value, String kind, UUID id, Map<ElementKey, String> provenance) {
        return new ShapingView.Text(value, provenance.get(new ElementKey(kind, id)));
    }

    private int nextPosition(UUID sessionId) {
        return jdbc.sql("""
                SELECT coalesce(max(position), -1) + 1
                FROM discovery_shaping_messages WHERE shaping_session_id = ?
                """).param(sessionId).query(Integer.class).single();
    }

    private List<StoredMessage> messages(UUID sessionId) {
        return jdbc.sql("""
                SELECT id, author_kind, content, created_at
                FROM discovery_shaping_messages WHERE shaping_session_id = ? ORDER BY position
                """).param(sessionId).query((rs, row) -> new StoredMessage(
                        rs.getObject("id", UUID.class), rs.getString("author_kind"), rs.getString("content"),
                        rs.getTimestamp("created_at").toInstant())).list();
    }

    private static void requireText(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("Pi returned an invalid " + name + ".");
        }
    }

    private static void requireAllowed(String value, Set<String> allowed, String name) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("Pi returned an invalid " + name + ".");
        }
    }

    private static void requireList(List<?> values, int min, int max, String name) {
        if (values == null || values.size() < min || values.size() > max || values.contains(null)) {
            throw new IllegalArgumentException("Pi returned invalid " + name + " entries.");
        }
    }

    public record Work(UUID id, UUID organisationId, UUID sessionId, UUID triggerMessageId,
            int attempts, UUID leaseOwner, UUID originCorrelationId, UUID executionCorrelationId) {}
    public record Context(UUID workId, UUID sessionId, String title, String objective, List<StoredMessage> messages) {}
    public record StoredMessage(UUID id, String authorKind, String content, Instant createdAt) {}
    private record DiscoveryContext(String title, String objective) {}
    private record LatestWork(String status, UUID responseMessageId) {}
    private record Candidate(UUID id, UUID organisationId) {}
    private record ElementKey(String kind, UUID id) {}
    private record ProvenanceRow(String kind, UUID id, String summary) {}
    private record ProposalRow(
            UUID id,
            String status,
            String objective,
            String desiredOutcome,
            String intendedInterviewee,
            String intervieweeRelevance,
            String completionCriteria,
            String expectedCommitment,
            String dataUseSummary) {}
}
