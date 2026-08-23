package com.findworks.interview;

import com.findworks.security.PilotTenant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
class MissionRepository {

    private static final Set<String> PRIORITIES = Set.of("high", "medium", "low");
    private static final Set<String> OUTCOMES = Set.of(
            "supported_knowledge", "unknown", "conflict", "ownership_gap");
    private static final Set<String> REGENERATABLE = Set.of(
            "objective", "desired_outcome", "interviewee", "contexts", "boundaries", "terminology",
            "opening_questions", "completion_criteria", "expected_commitment", "data_use_summary",
            "investigation_items", "ambiguities");

    private final JdbcClient jdbc;
    private final PilotTenant tenant;

    MissionRepository(JdbcClient jdbc, PilotTenant tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Transactional
    UUID confirmProposal(UUID proposalId, String email) {
        var investigator = tenant.investigator(email);
        var proposal = jdbc.sql("""
                SELECT p.id, p.organisation_id, p.shaping_session_id, p.status, s.discovery_id
                FROM interview_mission_proposals p
                JOIN discovery_shaping_sessions s ON s.id = p.shaping_session_id
                JOIN discoveries d ON d.id = s.discovery_id
                WHERE p.id = ? AND d.owner_membership_id = ? AND d.status = 'active'
                FOR UPDATE OF p
                """).params(proposalId, investigator.membershipId()).query((rs, row) -> new ProposalRow(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("shaping_session_id", UUID.class), rs.getString("status"),
                        rs.getObject("discovery_id", UUID.class))).optional()
                .orElseThrow(() -> new AccessDeniedException("Mission proposal access denied."));
        var existing = jdbc.sql("""
                SELECT id FROM interview_missions
                WHERE source_proposal_id = ? ORDER BY version LIMIT 1
                """).param(proposal.id()).query(UUID.class).optional();
        if (existing.isPresent()) {
            return existing.get();
        }
        var source = proposal(proposal);
        var missionId = UUID.randomUUID();
        writeVersion(missionId, missionId, 1, proposal.discoveryId(), investigator.organisationId(),
                source.edit(), null, source, allProposalKinds(), investigator.membershipId(), proposal.id());
        jdbc.sql("""
                UPDATE interview_mission_proposals
                SET status = 'confirmed', confirmed_at = now()
                WHERE id = ? AND status = 'awaiting_confirmation'
                """).param(proposal.id()).update();
        tenant.audit(investigator, "mission_proposal_confirmed", "interview_mission", missionId);
        return missionId;
    }

    @Transactional(readOnly = true)
    Mission mission(UUID missionId, String email) {
        var investigator = tenant.investigator(email);
        var row = row(missionId, investigator.membershipId(), false);
        var loaded = load(row);
        var missing = readiness(loaded.edit());
        var provenance = provenance(missionId);
        var history = jdbc.sql("""
                SELECT id, version, status, created_at FROM interview_missions
                WHERE lineage_id = ? ORDER BY version DESC
                """).param(row.lineageId()).query((rs, ignored) -> new History(
                        rs.getObject("id", UUID.class), rs.getInt("version"), rs.getString("status"),
                        rs.getTimestamp("created_at").toInstant())).list();
        var latestProposal = jdbc.sql("""
                SELECT p.id FROM interview_mission_proposals p
                JOIN discovery_shaping_sessions s ON s.id = p.shaping_session_id
                WHERE s.discovery_id = ? ORDER BY p.created_at DESC LIMIT 1
                """).param(row.discoveryId()).query(UUID.class).optional().orElse(null);
        var contexts = withContextProvenance(loaded, provenance);
        var boundaries = withBoundaryProvenance(loaded, provenance);
        var terms = withTermProvenance(loaded, provenance);
        var questions = withQuestionProvenance(loaded, provenance);
        var items = withItemProvenance(loaded, provenance);
        var ambiguities = withAmbiguityProvenance(loaded, provenance);
        return new Mission(
                row.id(), row.discoveryId(), row.lineageId(), row.version(), row.status(), row.title(),
                text(loaded.edit().objective(), "objective", row.id(), provenance),
                text(loaded.edit().desiredOutcome(), "desired_outcome", row.id(), provenance),
                text(loaded.edit().intendedInterviewee(), "intended_interviewee", row.id(), provenance),
                text(loaded.edit().intervieweeRelevance(), "interviewee_relevance", row.id(), provenance),
                contexts, boundaries, terms, questions,
                text(loaded.edit().completionCriteria(), "completion_criteria", row.id(), provenance),
                text(loaded.edit().expectedCommitment(), "expected_commitment", row.id(), provenance),
                text(loaded.edit().dataUseSummary(), "data_use_summary", row.id(), provenance),
                items, ambiguities, missing.isEmpty(), missing, history, latestProposal);
    }

    @Transactional
    UUID save(UUID baseVersionId, String email, MissionEdit edit) {
        var investigator = tenant.investigator(email);
        validate(edit);
        var baseRow = row(baseVersionId, investigator.membershipId(), true);
        requireLatestDraftOrApproved(baseRow);
        var base = load(baseRow);
        if (base.edit().equals(edit)) {
            return baseVersionId;
        }
        return nextVersion(baseRow, base, edit, null, Set.of(), investigator);
    }

    @Transactional
    UUID regenerate(UUID baseVersionId, UUID proposalId, String section, String email) {
        if (!REGENERATABLE.contains(section)) {
            throw new IllegalArgumentException("Choose a valid Mission section to regenerate.");
        }
        var investigator = tenant.investigator(email);
        var baseRow = row(baseVersionId, investigator.membershipId(), true);
        requireLatestDraftOrApproved(baseRow);
        var proposalRow = jdbc.sql("""
                SELECT p.id, p.organisation_id, p.shaping_session_id, p.status, s.discovery_id
                FROM interview_mission_proposals p
                JOIN discovery_shaping_sessions s ON s.id = p.shaping_session_id
                WHERE p.id = ? AND s.discovery_id = ?
                """).params(proposalId, baseRow.discoveryId()).query((rs, ignored) -> new ProposalRow(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("shaping_session_id", UUID.class), rs.getString("status"),
                        rs.getObject("discovery_id", UUID.class))).optional()
                .orElseThrow(() -> new IllegalArgumentException("That proposal does not belong to this Discovery."));
        var source = proposal(proposalRow);
        var base = load(baseRow);
        var edit = replace(base.edit(), source.edit(), section);
        validate(edit);
        if (base.edit().equals(edit)) {
            return baseVersionId;
        }
        return nextVersion(baseRow, base, edit, source, kinds(section), investigator);
    }

    @Transactional
    void approve(UUID missionId, String email) {
        var investigator = tenant.investigator(email);
        var row = row(missionId, investigator.membershipId(), true);
        if (!"draft".equals(row.status()) || !isLatest(row)) {
            throw new IllegalArgumentException("That Mission version is stale and cannot be approved.");
        }
        var missing = readiness(load(row).edit());
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Mission is not ready: " + String.join("; ", missing));
        }
        jdbc.sql("""
                UPDATE interview_missions SET status = 'approved', approved_at = now()
                WHERE id = ? AND status = 'draft'
                """).param(missionId).update();
        tenant.audit(investigator, "mission_approved", "interview_mission", missionId);
    }

    private UUID nextVersion(Row baseRow, Loaded base, MissionEdit edit, ProposalLoaded proposal,
            Set<String> proposalKinds, PilotTenant.Investigator investigator) {
        var newId = UUID.randomUUID();
        jdbc.sql("""
                UPDATE interview_missions SET status = 'superseded', superseded_at = now()
                WHERE id = ? AND status IN ('draft', 'approved')
                """).param(baseRow.id()).update();
        writeVersion(newId, baseRow.lineageId(), baseRow.version() + 1, baseRow.discoveryId(),
                baseRow.organisationId(), edit, base, proposal, proposalKinds,
                investigator.membershipId(), proposal == null ? null : proposal.row().id());
        jdbc.sql("UPDATE discoveries SET updated_at = now(), last_activity_at = now() WHERE id = ?")
                .param(baseRow.discoveryId()).update();
        tenant.audit(investigator, proposal == null ? "mission_version_saved" : "mission_section_regenerated",
                "interview_mission", newId);
        return newId;
    }

    private void writeVersion(UUID missionId, UUID lineageId, int version, UUID discoveryId, UUID organisationId,
            MissionEdit edit, Loaded base, ProposalLoaded proposal, Set<String> proposalKinds,
            UUID actorId, UUID sourceProposalId) {
        jdbc.sql("""
                INSERT INTO interview_missions (
                    id, organisation_id, discovery_id, lineage_id, source_proposal_id, version, status,
                    interviewee_name, interviewee_email, objective, desired_outcome, interviewee_relevance,
                    completion_criteria, expected_commitment, data_use_summary
                ) VALUES (?, ?, ?, ?, ?, ?, 'draft', ?, NULL, ?, ?, ?, ?, ?, ?)
                """).params(missionId, organisationId, discoveryId, lineageId, sourceProposalId, version,
                edit.intendedInterviewee(), edit.objective(), edit.desiredOutcome(), edit.intervieweeRelevance(),
                edit.completionCriteria(), edit.expectedCommitment(), edit.dataUseSummary()).update();
        writeElementProvenance(missionId, "objective", missionId, key("objective"),
                base == null || edit.objective().equals(base.edit().objective()), base, proposal,
                proposalKinds, actorId, organisationId);
        writeElementProvenance(missionId, "desired_outcome", missionId, key("desired_outcome"),
                base == null || edit.desiredOutcome().equals(base.edit().desiredOutcome()), base, proposal,
                proposalKinds, actorId, organisationId);
        writeElementProvenance(missionId, "intended_interviewee", missionId, key("intended_interviewee"),
                base == null || edit.intendedInterviewee().equals(base.edit().intendedInterviewee()), base, proposal,
                proposalKinds, actorId, organisationId);
        writeElementProvenance(missionId, "interviewee_relevance", missionId, key("interviewee_relevance"),
                base == null || edit.intervieweeRelevance().equals(base.edit().intervieweeRelevance()), base, proposal,
                proposalKinds, actorId, organisationId);
        writeElementProvenance(missionId, "completion_criteria", missionId, key("completion_criteria"),
                base == null || edit.completionCriteria().equals(base.edit().completionCriteria()), base, proposal,
                proposalKinds, actorId, organisationId);
        writeElementProvenance(missionId, "expected_commitment", missionId, key("expected_commitment"),
                base == null || edit.expectedCommitment().equals(base.edit().expectedCommitment()), base, proposal,
                proposalKinds, actorId, organisationId);
        writeElementProvenance(missionId, "data_use_summary", missionId, key("data_use_summary"),
                base == null || edit.dataUseSummary().equals(base.edit().dataUseSummary()), base, proposal,
                proposalKinds, actorId, organisationId);

        for (var position = 0; position < edit.contexts().size(); position++) {
            var value = edit.contexts().get(position);
            var id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO mission_contexts
                        (id, organisation_id, interview_mission_id, position, visibility, content)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(id, organisationId, missionId, position, value.visibility(), value.content()).update();
            writeElementProvenance(missionId, "context", id, key("context", position),
                    unchanged(base, position, value, base == null ? List.of() : base.edit().contexts()),
                    base, proposal, proposalKinds, actorId, organisationId);
        }
        for (var position = 0; position < edit.boundaries().size(); position++) {
            var value = edit.boundaries().get(position);
            var id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO mission_boundaries
                        (id, organisation_id, interview_mission_id, position, boundary_kind, content)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(id, organisationId, missionId, position, value.kind(), value.content()).update();
            writeElementProvenance(missionId, "boundary", id, key("boundary", position),
                    unchanged(base, position, value, base == null ? List.of() : base.edit().boundaries()),
                    base, proposal, proposalKinds, actorId, organisationId);
        }
        for (var position = 0; position < edit.terminology().size(); position++) {
            var value = edit.terminology().get(position);
            var id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO mission_terms
                        (id, organisation_id, interview_mission_id, position, term, meaning)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """).params(id, organisationId, missionId, position, value.term(), value.meaning()).update();
            writeElementProvenance(missionId, "term", id, key("term", position),
                    unchanged(base, position, value, base == null ? List.of() : base.edit().terminology()),
                    base, proposal, proposalKinds, actorId, organisationId);
        }
        for (var position = 0; position < edit.openingQuestions().size(); position++) {
            var value = edit.openingQuestions().get(position);
            var id = UUID.randomUUID();
            jdbc.sql("""
                    INSERT INTO mission_opening_questions
                        (id, organisation_id, interview_mission_id, position, question)
                    VALUES (?, ?, ?, ?, ?)
                    """).params(id, organisationId, missionId, position, value).update();
            writeElementProvenance(missionId, "opening_question", id, key("opening_question", position),
                    unchanged(base, position, value, base == null ? List.of() : base.edit().openingQuestions()),
                    base, proposal, proposalKinds, actorId, organisationId);
        }

        var itemIds = new ArrayList<UUID>();
        for (var position = 0; position < edit.investigationItems().size(); position++) {
            var value = edit.investigationItems().get(position);
            var id = UUID.randomUUID();
            itemIds.add(id);
            jdbc.sql("""
                    INSERT INTO investigation_items (
                        id, organisation_id, interview_mission_id, position, knowledge_gap, opening_question,
                        required, importance, priority, relevant_context
                    ) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)
                    """).params(id, organisationId, missionId, position, value.knowledgeGap(), value.required(),
                    value.importance(), value.priority(), value.relevantContext()).update();
            var sameItem = unchanged(base, position, value,
                    base == null ? List.of() : base.edit().investigationItems());
            writeElementProvenance(missionId, "investigation_item", id, key("investigation_item", position),
                    sameItem, base, proposal, proposalKinds, actorId, organisationId);
            for (var outcomePosition = 0; outcomePosition < value.allowedOutcomes().size(); outcomePosition++) {
                var outcome = value.allowedOutcomes().get(outcomePosition);
                var outcomeId = UUID.randomUUID();
                jdbc.sql("""
                        INSERT INTO mission_allowed_outcomes (
                            id, organisation_id, interview_mission_id, investigation_item_id, position, outcome_kind
                        ) VALUES (?, ?, ?, ?, ?, ?)
                        """).params(outcomeId, organisationId, missionId, id, outcomePosition, outcome).update();
                writeElementProvenance(missionId, "allowed_outcome", outcomeId,
                        key("allowed_outcome", position, outcomePosition), sameItem,
                        base, proposal, proposalKinds, actorId, organisationId);
            }
        }
        for (var position = 0; position < edit.ambiguities().size(); position++) {
            var value = edit.ambiguities().get(position);
            var id = UUID.randomUUID();
            var representedBy = value.representedByItemPosition() == null ? null
                    : itemIds.get(value.representedByItemPosition());
            jdbc.sql("""
                    INSERT INTO mission_ambiguities (
                        id, organisation_id, interview_mission_id, position, content, represented_by_item_id
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """).params(id, organisationId, missionId, position, value.content(), representedBy).update();
            writeElementProvenance(missionId, "ambiguity", id, key("ambiguity", position),
                    unchanged(base, position, value, base == null ? List.of() : base.edit().ambiguities()),
                    base, proposal, proposalKinds, actorId, organisationId);
        }
    }

    private void writeElementProvenance(UUID missionId, String kind, UUID elementId, ElementKey key,
            boolean unchanged, Loaded base, ProposalLoaded proposal, Set<String> proposalKinds,
            UUID actorId, UUID organisationId) {
        if (proposal != null && proposalKinds.contains(kind)) {
            var proposalElementId = proposal.elementIds().get(key);
            if (proposalElementId == null) {
                throw new IllegalArgumentException("The proposal does not contain that Mission section.");
            }
            jdbc.sql("""
                    INSERT INTO mission_element_provenance (
                        id, organisation_id, interview_mission_id, element_kind, element_id, source_kind,
                        source_proposal_id, shaping_session_id, source_message_id, source_runtime_work_id,
                        actor_membership_id, created_at
                    )
                    SELECT gen_random_uuid(), ?, ?, ?, ?,
                           CASE WHEN source_kind = 'investigator_message'
                                THEN 'investigator_message' ELSE 'agent_proposal_confirmed' END,
                           proposal_id, shaping_session_id, source_message_id, source_runtime_work_id,
                           CASE WHEN source_kind = 'agent_proposal' THEN ? ELSE NULL END,
                           CASE WHEN source_kind = 'agent_proposal' THEN now() ELSE created_at END
                    FROM mission_proposal_provenance
                    WHERE proposal_id = ? AND element_kind = ? AND element_id = ?
                    """).params(organisationId, missionId, kind, elementId, actorId,
                    proposal.row().id(), kind, proposalElementId).update();
            return;
        }
        if (base != null && unchanged) {
            var oldElementId = base.elementIds().get(key);
            jdbc.sql("""
                    INSERT INTO mission_element_provenance (
                        id, organisation_id, interview_mission_id, element_kind, element_id, source_kind,
                        source_proposal_id, shaping_session_id, source_message_id, source_runtime_work_id,
                        actor_membership_id, created_at
                    )
                    SELECT gen_random_uuid(), ?, ?, element_kind, ?, source_kind, source_proposal_id,
                           shaping_session_id, source_message_id, source_runtime_work_id,
                           actor_membership_id, created_at
                    FROM mission_element_provenance
                    WHERE interview_mission_id = ? AND element_kind = ? AND element_id = ?
                    """).params(organisationId, missionId, elementId, base.row().id(), kind, oldElementId).update();
            return;
        }
        jdbc.sql("""
                INSERT INTO mission_element_provenance (
                    id, organisation_id, interview_mission_id, element_kind, element_id,
                    source_kind, actor_membership_id
                ) VALUES (?, ?, ?, ?, ?, 'investigator_edit', ?)
                """).params(UUID.randomUUID(), organisationId, missionId, kind, elementId, actorId).update();
    }

    private Loaded load(Row row) {
        var ids = new HashMap<ElementKey, UUID>();
        ids.put(key("objective"), row.id());
        ids.put(key("desired_outcome"), row.id());
        ids.put(key("intended_interviewee"), row.id());
        ids.put(key("interviewee_relevance"), row.id());
        ids.put(key("completion_criteria"), row.id());
        ids.put(key("expected_commitment"), row.id());
        ids.put(key("data_use_summary"), row.id());
        var contexts = jdbc.sql("""
                SELECT id, visibility, content FROM mission_contexts
                WHERE interview_mission_id = ? ORDER BY position
                """).param(row.id()).query((rs, index) -> {
                    ids.put(key("context", index), rs.getObject("id", UUID.class));
                    return new MissionEdit.Context(rs.getString("visibility"), rs.getString("content"));
                }).list();
        var boundaries = jdbc.sql("""
                SELECT id, boundary_kind, content FROM mission_boundaries
                WHERE interview_mission_id = ? ORDER BY position
                """).param(row.id()).query((rs, index) -> {
                    ids.put(key("boundary", index), rs.getObject("id", UUID.class));
                    return new MissionEdit.Boundary(rs.getString("boundary_kind"), rs.getString("content"));
                }).list();
        var terms = jdbc.sql("""
                SELECT id, term, meaning FROM mission_terms
                WHERE interview_mission_id = ? ORDER BY position
                """).param(row.id()).query((rs, index) -> {
                    ids.put(key("term", index), rs.getObject("id", UUID.class));
                    return new MissionEdit.Term(rs.getString("term"), rs.getString("meaning"));
                }).list();
        var questions = jdbc.sql("""
                SELECT id, question FROM mission_opening_questions
                WHERE interview_mission_id = ? ORDER BY position
                """).param(row.id()).query((rs, index) -> {
                    ids.put(key("opening_question", index), rs.getObject("id", UUID.class));
                    return rs.getString("question");
                }).list();
        var items = jdbc.sql("""
                SELECT id, knowledge_gap, importance, priority, relevant_context, required
                FROM investigation_items WHERE interview_mission_id = ? ORDER BY position
                """).param(row.id()).query((rs, index) -> {
                    var itemId = rs.getObject("id", UUID.class);
                    ids.put(key("investigation_item", index), itemId);
                    var outcomes = jdbc.sql("""
                            SELECT id, outcome_kind FROM mission_allowed_outcomes
                            WHERE investigation_item_id = ? ORDER BY position
                            """).param(itemId).query((outcome, outcomeIndex) -> {
                                ids.put(key("allowed_outcome", index, outcomeIndex),
                                        outcome.getObject("id", UUID.class));
                                return outcome.getString("outcome_kind");
                            }).list();
                    return new MissionEdit.Item(rs.getString("knowledge_gap"), rs.getString("importance"),
                            rs.getString("priority"), rs.getString("relevant_context"),
                            rs.getBoolean("required"), outcomes);
                }).list();
        var itemPositions = jdbc.sql("""
                SELECT id, position FROM investigation_items WHERE interview_mission_id = ?
                """).param(row.id()).query((rs, ignored) -> Map.entry(
                        rs.getObject("id", UUID.class), rs.getInt("position"))).list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var ambiguities = jdbc.sql("""
                SELECT id, content, represented_by_item_id FROM mission_ambiguities
                WHERE interview_mission_id = ? ORDER BY position
                """).param(row.id()).query((rs, index) -> {
                    ids.put(key("ambiguity", index), rs.getObject("id", UUID.class));
                    var represented = rs.getObject("represented_by_item_id", UUID.class);
                    return new MissionEdit.Ambiguity(rs.getString("content"),
                            represented == null ? null : itemPositions.get(represented));
                }).list();
        return new Loaded(row, new MissionEdit(
                row.objective(), row.desiredOutcome(), row.intervieweeName(), row.intervieweeRelevance(),
                contexts, boundaries, terms, questions, row.completionCriteria(), row.expectedCommitment(),
                row.dataUseSummary(), items, ambiguities), Map.copyOf(ids));
    }

    private ProposalLoaded proposal(ProposalRow row) {
        var ids = new HashMap<ElementKey, UUID>();
        ids.put(key("objective"), row.id());
        ids.put(key("desired_outcome"), row.id());
        ids.put(key("intended_interviewee"), row.id());
        ids.put(key("interviewee_relevance"), row.id());
        ids.put(key("completion_criteria"), row.id());
        ids.put(key("expected_commitment"), row.id());
        ids.put(key("data_use_summary"), row.id());
        var scalar = jdbc.sql("""
                SELECT objective, desired_outcome, intended_interviewee, interviewee_relevance,
                       completion_criteria, expected_commitment, data_use_summary
                FROM interview_mission_proposals WHERE id = ?
                """).param(row.id()).query((rs, ignored) -> new Scalar(
                        rs.getString("objective"), rs.getString("desired_outcome"),
                        rs.getString("intended_interviewee"), rs.getString("interviewee_relevance"),
                        rs.getString("completion_criteria"), rs.getString("expected_commitment"),
                        rs.getString("data_use_summary"))).single();
        var contexts = jdbc.sql("""
                SELECT id, visibility, content FROM mission_proposal_contexts
                WHERE proposal_id = ? ORDER BY position
                """).param(row.id()).query((rs, index) -> {
                    ids.put(key("context", index), rs.getObject("id", UUID.class));
                    return new MissionEdit.Context(rs.getString("visibility"), rs.getString("content"));
                }).list();
        var boundaries = jdbc.sql("""
                SELECT id, boundary_kind, content FROM mission_proposal_boundaries
                WHERE proposal_id = ? ORDER BY position
                """).param(row.id()).query((rs, index) -> {
                    ids.put(key("boundary", index), rs.getObject("id", UUID.class));
                    return new MissionEdit.Boundary(rs.getString("boundary_kind"), rs.getString("content"));
                }).list();
        var terms = jdbc.sql("""
                SELECT id, term, meaning FROM mission_proposal_terms WHERE proposal_id = ? ORDER BY position
                """).param(row.id()).query((rs, index) -> {
                    ids.put(key("term", index), rs.getObject("id", UUID.class));
                    return new MissionEdit.Term(rs.getString("term"), rs.getString("meaning"));
                }).list();
        var questions = jdbc.sql("""
                SELECT id, question FROM mission_proposal_opening_questions
                WHERE proposal_id = ? ORDER BY position
                """).param(row.id()).query((rs, index) -> {
                    ids.put(key("opening_question", index), rs.getObject("id", UUID.class));
                    return rs.getString("question");
                }).list();
        var items = jdbc.sql("""
                SELECT id, knowledge_gap, importance, priority, relevant_context, required
                FROM mission_proposal_investigation_items WHERE proposal_id = ? ORDER BY position
                """).param(row.id()).query((rs, index) -> {
                    var itemId = rs.getObject("id", UUID.class);
                    ids.put(key("investigation_item", index), itemId);
                    var outcomes = jdbc.sql("""
                            SELECT id, outcome_kind FROM mission_proposal_allowed_outcomes
                            WHERE investigation_item_id = ? ORDER BY position
                            """).param(itemId).query((outcome, outcomeIndex) -> {
                                ids.put(key("allowed_outcome", index, outcomeIndex),
                                        outcome.getObject("id", UUID.class));
                                return outcome.getString("outcome_kind");
                            }).list();
                    return new MissionEdit.Item(rs.getString("knowledge_gap"), rs.getString("importance"),
                            rs.getString("priority"), rs.getString("relevant_context"),
                            rs.getBoolean("required"), outcomes);
                }).list();
        var itemPositions = jdbc.sql("""
                SELECT id, position FROM mission_proposal_investigation_items WHERE proposal_id = ?
                """).param(row.id()).query((rs, ignored) -> Map.entry(
                        rs.getObject("id", UUID.class), rs.getInt("position"))).list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var ambiguities = jdbc.sql("""
                SELECT id, content, represented_by_item_id FROM mission_proposal_ambiguities
                WHERE proposal_id = ? ORDER BY position
                """).param(row.id()).query((rs, index) -> {
                    ids.put(key("ambiguity", index), rs.getObject("id", UUID.class));
                    var represented = rs.getObject("represented_by_item_id", UUID.class);
                    return new MissionEdit.Ambiguity(rs.getString("content"),
                            represented == null ? null : itemPositions.get(represented));
                }).list();
        return new ProposalLoaded(row, new MissionEdit(
                scalar.objective(), scalar.desiredOutcome(), scalar.intendedInterviewee(), scalar.relevance(),
                contexts, boundaries, terms, questions, scalar.completionCriteria(), scalar.expectedCommitment(),
                scalar.dataUseSummary(), items, ambiguities), Map.copyOf(ids));
    }

    private Row row(UUID id, UUID membershipId, boolean lock) {
        var suffix = lock ? " FOR UPDATE OF m" : "";
        return jdbc.sql("""
                SELECT m.id, m.organisation_id, m.discovery_id, m.lineage_id, m.version, m.status,
                       m.interviewee_name, m.objective, m.desired_outcome, m.interviewee_relevance,
                       m.completion_criteria, m.expected_commitment, m.data_use_summary,
                       d.title, m.created_at
                FROM interview_missions m
                JOIN discoveries d ON d.id = m.discovery_id
                WHERE m.id = ? AND d.owner_membership_id = ? AND d.status = 'active'
                """ + suffix).params(id, membershipId).query((rs, ignored) -> new Row(
                        rs.getObject("id", UUID.class), rs.getObject("organisation_id", UUID.class),
                        rs.getObject("discovery_id", UUID.class), rs.getObject("lineage_id", UUID.class),
                        rs.getInt("version"), rs.getString("status"), rs.getString("title"),
                        rs.getString("objective"), rs.getString("desired_outcome"),
                        rs.getString("interviewee_name"), rs.getString("interviewee_relevance"),
                        rs.getString("completion_criteria"), rs.getString("expected_commitment"),
                        rs.getString("data_use_summary"), rs.getTimestamp("created_at").toInstant()))
                .optional().orElseThrow(() -> new AccessDeniedException("Mission access denied."));
    }

    private void requireLatestDraftOrApproved(Row row) {
        if (!("draft".equals(row.status()) || "approved".equals(row.status())) || !isLatest(row)) {
            throw new IllegalArgumentException("That Mission version is stale. Reload before saving.");
        }
    }

    private boolean isLatest(Row row) {
        return jdbc.sql("SELECT id FROM interview_missions WHERE lineage_id = ? ORDER BY version DESC LIMIT 1")
                .param(row.lineageId()).query(UUID.class).single().equals(row.id());
    }

    private List<String> readiness(MissionEdit edit) {
        var missing = new ArrayList<String>();
        missing(edit.objective(), "objective", missing);
        missing(edit.desiredOutcome(), "desired outcome", missing);
        missing(edit.intendedInterviewee(), "intended interviewee", missing);
        missing(edit.intervieweeRelevance(), "interviewee relevance", missing);
        missing(edit.completionCriteria(), "completion criteria", missing);
        missing(edit.expectedCommitment(), "expected commitment", missing);
        missing(edit.dataUseSummary(), "data-use summary", missing);
        if (edit.contexts().stream().noneMatch(context -> "shared".equals(context.visibility()))) {
            missing.add("shared context");
        }
        if (edit.boundaries().stream().noneMatch(boundary -> "boundary".equals(boundary.kind()))) {
            missing.add("boundary");
        }
        if (edit.boundaries().stream().noneMatch(boundary -> "prohibited_topic".equals(boundary.kind()))) {
            missing.add("prohibited topics");
        }
        if (edit.openingQuestions().isEmpty()) {
            missing.add("proposed opening question");
        }
        if (edit.investigationItems().isEmpty()
                || edit.investigationItems().stream().noneMatch(MissionEdit.Item::required)) {
            missing.add("required Investigation Item");
        }
        if (edit.ambiguities().stream().anyMatch(value -> value.representedByItemPosition() == null)) {
            missing.add("an unresolved ambiguity is not represented by an Investigation Item");
        }
        return List.copyOf(missing);
    }

    private void validate(MissionEdit edit) {
        if (edit == null) {
            throw new IllegalArgumentException("Mission content is missing.");
        }
        max(edit.objective(), 4_000, "objective");
        max(edit.desiredOutcome(), 4_000, "desired outcome");
        max(edit.intendedInterviewee(), 1_000, "intended interviewee");
        max(edit.intervieweeRelevance(), 4_000, "interviewee relevance");
        max(edit.completionCriteria(), 4_000, "completion criteria");
        max(edit.expectedCommitment(), 1_000, "expected commitment");
        max(edit.dataUseSummary(), 4_000, "data use summary");
        edit.contexts().forEach(value -> required(value.content(), 4_000, "context"));
        edit.boundaries().forEach(value -> required(value.content(), 4_000, "boundary"));
        edit.terminology().forEach(value -> {
            required(value.term(), 300, "term");
            required(value.meaning(), 2_000, "term meaning");
        });
        edit.openingQuestions().forEach(value -> required(value, 2_000, "opening question"));
        for (var item : edit.investigationItems()) {
            required(item.knowledgeGap(), 4_000, "Investigation Item knowledge gap");
            required(item.importance(), 4_000, "Investigation Item importance");
            required(item.relevantContext(), 4_000, "Investigation Item context");
            if (!PRIORITIES.contains(item.priority())) {
                throw new IllegalArgumentException("Choose a valid Investigation Item priority.");
            }
            if (item.allowedOutcomes().isEmpty() || !OUTCOMES.containsAll(item.allowedOutcomes())
                    || new HashSet<>(item.allowedOutcomes()).size() != item.allowedOutcomes().size()) {
                throw new IllegalArgumentException("Choose valid, unique allowed outcomes.");
            }
        }
        edit.ambiguities().forEach(value -> {
            required(value.content(), 4_000, "ambiguity");
            if (value.representedByItemPosition() != null
                    && (value.representedByItemPosition() < 0
                    || value.representedByItemPosition() >= edit.investigationItems().size())) {
                throw new IllegalArgumentException("Ambiguity references an unknown Investigation Item.");
            }
        });
    }

    private MissionEdit replace(MissionEdit base, MissionEdit proposal, String section) {
        return new MissionEdit(
                "objective".equals(section) ? proposal.objective() : base.objective(),
                "desired_outcome".equals(section) ? proposal.desiredOutcome() : base.desiredOutcome(),
                "interviewee".equals(section) ? proposal.intendedInterviewee() : base.intendedInterviewee(),
                "interviewee".equals(section) ? proposal.intervieweeRelevance() : base.intervieweeRelevance(),
                "contexts".equals(section) ? proposal.contexts() : base.contexts(),
                "boundaries".equals(section) ? proposal.boundaries() : base.boundaries(),
                "terminology".equals(section) ? proposal.terminology() : base.terminology(),
                "opening_questions".equals(section) ? proposal.openingQuestions() : base.openingQuestions(),
                "completion_criteria".equals(section) ? proposal.completionCriteria() : base.completionCriteria(),
                "expected_commitment".equals(section) ? proposal.expectedCommitment() : base.expectedCommitment(),
                "data_use_summary".equals(section) ? proposal.dataUseSummary() : base.dataUseSummary(),
                "investigation_items".equals(section) ? proposal.investigationItems() : base.investigationItems(),
                "ambiguities".equals(section) ? proposal.ambiguities() : base.ambiguities());
    }

    private Set<String> kinds(String section) {
        return switch (section) {
            case "interviewee" -> Set.of("intended_interviewee", "interviewee_relevance");
            case "contexts" -> Set.of("context");
            case "boundaries" -> Set.of("boundary");
            case "terminology" -> Set.of("term");
            case "opening_questions" -> Set.of("opening_question");
            case "investigation_items" -> Set.of("investigation_item", "allowed_outcome");
            case "ambiguities" -> Set.of("ambiguity");
            default -> Set.of(section);
        };
    }

    private Set<String> allProposalKinds() {
        var result = new HashSet<String>();
        REGENERATABLE.forEach(section -> result.addAll(kinds(section)));
        return Set.copyOf(result);
    }

    private Map<ElementKey, String> provenance(UUID missionId) {
        return jdbc.sql("""
                SELECT element_kind, element_id,
                       CASE WHEN bool_or(source_kind = 'investigator_edit')
                            THEN 'Edited by Investigator at ' || to_char(max(created_at), 'YYYY-MM-DD HH24:MI TZ')
                            WHEN bool_or(source_kind = 'agent_proposal_confirmed')
                            THEN 'Agent proposal confirmed by Investigator'
                            ELSE 'Linked to ' || count(source_message_id) || ' Investigator statement'
                                 || CASE WHEN count(source_message_id) = 1 THEN '' ELSE 's' END
                       END summary
                FROM mission_element_provenance WHERE interview_mission_id = ?
                GROUP BY element_kind, element_id
                """).param(missionId).query((rs, ignored) -> Map.entry(
                        new ElementKey(rs.getString("element_kind"), rs.getObject("element_id", UUID.class), -1, -1),
                        rs.getString("summary"))).list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private List<Context> withContextProvenance(Loaded loaded, Map<ElementKey, String> provenance) {
        var result = new ArrayList<Context>();
        for (var index = 0; index < loaded.edit().contexts().size(); index++) {
            var value = loaded.edit().contexts().get(index);
            result.add(new Context(value.visibility(), value.content(), summary(
                    "context", loaded.elementIds().get(key("context", index)), provenance)));
        }
        return List.copyOf(result);
    }

    private List<Boundary> withBoundaryProvenance(Loaded loaded, Map<ElementKey, String> provenance) {
        var result = new ArrayList<Boundary>();
        for (var index = 0; index < loaded.edit().boundaries().size(); index++) {
            var value = loaded.edit().boundaries().get(index);
            result.add(new Boundary(value.kind(), value.content(), summary(
                    "boundary", loaded.elementIds().get(key("boundary", index)), provenance)));
        }
        return List.copyOf(result);
    }

    private List<Term> withTermProvenance(Loaded loaded, Map<ElementKey, String> provenance) {
        var result = new ArrayList<Term>();
        for (var index = 0; index < loaded.edit().terminology().size(); index++) {
            var value = loaded.edit().terminology().get(index);
            result.add(new Term(value.term(), value.meaning(), summary(
                    "term", loaded.elementIds().get(key("term", index)), provenance)));
        }
        return List.copyOf(result);
    }

    private List<OpeningQuestion> withQuestionProvenance(Loaded loaded, Map<ElementKey, String> provenance) {
        var result = new ArrayList<OpeningQuestion>();
        for (var index = 0; index < loaded.edit().openingQuestions().size(); index++) {
            result.add(new OpeningQuestion(loaded.edit().openingQuestions().get(index), summary(
                    "opening_question", loaded.elementIds().get(key("opening_question", index)), provenance)));
        }
        return List.copyOf(result);
    }

    private List<Item> withItemProvenance(Loaded loaded, Map<ElementKey, String> provenance) {
        var result = new ArrayList<Item>();
        for (var index = 0; index < loaded.edit().investigationItems().size(); index++) {
            var value = loaded.edit().investigationItems().get(index);
            result.add(new Item(index, value.knowledgeGap(), value.importance(), value.priority(),
                    value.relevantContext(), value.required(), value.allowedOutcomes(), summary(
                            "investigation_item", loaded.elementIds().get(key("investigation_item", index)), provenance)));
        }
        return List.copyOf(result);
    }

    private List<Ambiguity> withAmbiguityProvenance(Loaded loaded, Map<ElementKey, String> provenance) {
        var result = new ArrayList<Ambiguity>();
        for (var index = 0; index < loaded.edit().ambiguities().size(); index++) {
            var value = loaded.edit().ambiguities().get(index);
            result.add(new Ambiguity(value.content(), value.representedByItemPosition(), summary(
                    "ambiguity", loaded.elementIds().get(key("ambiguity", index)), provenance)));
        }
        return List.copyOf(result);
    }

    private Text text(String value, String kind, UUID id, Map<ElementKey, String> provenance) {
        return new Text(value, summary(kind, id, provenance));
    }

    private String summary(String kind, UUID id, Map<ElementKey, String> provenance) {
        return provenance.get(new ElementKey(kind, id, -1, -1));
    }

    private static <T> boolean unchanged(Loaded base, int position, T value, List<T> old) {
        return base == null || (position < old.size() && value.equals(old.get(position)));
    }

    private static void missing(String value, String name, List<String> missing) {
        if (value.isBlank()) {
            missing.add(name);
        }
    }

    private static void max(String value, int max, String name) {
        if (value == null || value.length() > max) {
            throw new IllegalArgumentException("Mission " + name + " is too long.");
        }
    }

    private static void required(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("Mission " + name + " is invalid.");
        }
    }

    private static ElementKey key(String kind) {
        return new ElementKey(kind, null, -1, -1);
    }

    private static ElementKey key(String kind, int position) {
        return new ElementKey(kind, null, position, -1);
    }

    private static ElementKey key(String kind, int position, int subPosition) {
        return new ElementKey(kind, null, position, subPosition);
    }

    record Mission(
            UUID id,
            UUID discoveryId,
            UUID lineageId,
            int version,
            String status,
            String title,
            Text objective,
            Text desiredOutcome,
            Text intendedInterviewee,
            Text intervieweeRelevance,
            List<Context> contexts,
            List<Boundary> boundaries,
            List<Term> terminology,
            List<OpeningQuestion> openingQuestions,
            Text completionCriteria,
            Text expectedCommitment,
            Text dataUseSummary,
            List<Item> items,
            List<Ambiguity> ambiguities,
            boolean ready,
            List<String> missing,
            List<History> history,
            UUID latestProposalId) {

        public String sharedContextText() {
            return joinContexts("shared");
        }

        public String privateContextText() {
            return joinContexts("private");
        }

        public String boundaryText() {
            return joinBoundaries("boundary");
        }

        public String prohibitedTopicText() {
            return joinBoundaries("prohibited_topic");
        }

        public String terminologyText() {
            return terminology.stream().map(value -> value.term() + " | " + value.meaning())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        public String openingQuestionText() {
            return openingQuestions.stream().map(OpeningQuestion::question)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        public String ambiguityText() {
            return ambiguities.stream().map(value -> (value.representedByItemPosition() == null
                    ? "open" : Integer.toString(value.representedByItemPosition() + 1)) + " | " + value.content())
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        public boolean latest() {
            return !history.isEmpty() && id.equals(history.getFirst().id());
        }

        private String joinContexts(String visibility) {
            return contexts.stream().filter(value -> visibility.equals(value.visibility())).map(Context::content)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }

        private String joinBoundaries(String kind) {
            return boundaries.stream().filter(value -> kind.equals(value.kind())).map(Boundary::content)
                    .collect(java.util.stream.Collectors.joining("\n"));
        }
    }

    record Text(String value, String provenance) {}
    record Context(String visibility, String content, String provenance) {}
    record Boundary(String kind, String content, String provenance) {}
    record Term(String term, String meaning, String provenance) {}
    record OpeningQuestion(String question, String provenance) {}
    record Item(int position, String knowledgeGap, String importance, String priority,
            String relevantContext, boolean required, List<String> allowedOutcomes, String provenance) {
        public String allowedOutcomesText() {
            return String.join(", ", allowedOutcomes);
        }
    }
    record Ambiguity(String content, Integer representedByItemPosition, String provenance) {}
    record History(UUID id, int version, String status, Instant createdAt) {}

    private record Row(UUID id, UUID organisationId, UUID discoveryId, UUID lineageId, int version, String status,
            String title, String objective, String desiredOutcome, String intervieweeName, String intervieweeRelevance,
            String completionCriteria, String expectedCommitment, String dataUseSummary, Instant createdAt) {}
    private record ProposalRow(UUID id, UUID organisationId, UUID shapingSessionId, String status, UUID discoveryId) {}
    private record Scalar(String objective, String desiredOutcome, String intendedInterviewee, String relevance,
            String completionCriteria, String expectedCommitment, String dataUseSummary) {}
    private record ElementKey(String kind, UUID elementId, int position, int subPosition) {}
    private record Loaded(Row row, MissionEdit edit, Map<ElementKey, UUID> elementIds) {}
    private record ProposalLoaded(ProposalRow row, MissionEdit edit, Map<ElementKey, UUID> elementIds) {}
}
