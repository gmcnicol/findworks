package com.findworks.interview;

import com.findworks.runtime.FindingsExtractionRunner;
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
public class FindingsRepository {

    private static final Set<String> CATEGORIES = Set.of(
            "fact", "rule", "decision", "term", "exception", "assumption");
    private final JdbcClient jdbc;
    private final PilotTenant tenant;
    private final InterviewRuntimeRepository runtime;

    FindingsRepository(JdbcClient jdbc, PilotTenant tenant, InterviewRuntimeRepository runtime) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.runtime = runtime;
    }

    @Transactional
    public Prepared prepare(InterviewRuntimeRepository.Work work, String runtimeVersion) {
        tenant.select();
        if (!"findings_extraction".equals(work.workKind())
                || runtimeVersion == null || runtimeVersion.isBlank()) {
            throw new IllegalArgumentException("Runtime Run is not a Findings extraction.");
        }
        var mission = jdbc.sql("""
                SELECT m.objective, m.desired_outcome, m.completion_criteria,
                       m.expected_commitment, s.revision
                FROM interview_runtime_runs r
                JOIN interview_sessions s ON s.id = r.interview_session_id
                    AND s.interview_mission_id = r.interview_mission_id
                    AND s.organisation_id = r.organisation_id
                JOIN interview_missions m ON m.id = r.interview_mission_id
                    AND m.discovery_id = r.discovery_id AND m.organisation_id = r.organisation_id
                JOIN discoveries d ON d.id = r.discovery_id AND d.organisation_id = r.organisation_id
                WHERE r.id = ? AND r.lease_owner = ? AND r.status = 'running'
                  AND r.work_kind = 'findings_extraction' AND r.trigger = 'findings_extraction'
                  AND s.status = 'completed' AND s.revision = r.expected_revision
                  AND m.approved_at IS NOT NULL AND d.status = 'active'
                  AND NOT EXISTS (SELECT 1 FROM findings_packages p
                                  WHERE p.interview_session_id = r.interview_session_id)
                """).params(work.id(), work.leaseOwner()).query((rs, ignored) -> new Mission(
                        rs.getString("objective"), rs.getString("desired_outcome"),
                        rs.getString("completion_criteria"), rs.getString("expected_commitment")))
                .optional().orElseThrow(() -> new IllegalArgumentException("Findings extraction scope is stale."));
        var sharedContext = jdbc.sql("""
                SELECT content FROM mission_contexts
                WHERE interview_mission_id = ? AND visibility = 'shared' ORDER BY position
                """).param(work.missionId()).query(String.class).list();
        var items = jdbc.sql("""
                SELECT id, position, knowledge_gap, importance, priority, relevant_context, required
                FROM investigation_items WHERE interview_mission_id = ? ORDER BY position
                """).param(work.missionId()).query((rs, ignored) -> new Item(
                        rs.getObject("id", UUID.class), rs.getInt("position"),
                        rs.getString("knowledge_gap"), rs.getString("importance"),
                        rs.getString("priority"), rs.getString("relevant_context"),
                        rs.getBoolean("required"))).list();
        var evidence = jdbc.sql("""
                SELECT e.id, e.investigation_item_id, e.question_id, e.revises_evidence_id,
                       CASE WHEN a.assessment = 'out_of_scope' THEN NULL ELSE e.answer END answer,
                       e.source_type, e.created_at, p.id participant_id, p.intended_name,
                       coalesce(a.assessment, 'in_scope') scope,
                       NOT EXISTS (SELECT 1 FROM evidence child WHERE child.revises_evidence_id = e.id) current
                FROM evidence e
                JOIN discovery_participants p ON p.id = e.participant_id
                    AND p.discovery_id = e.discovery_id AND p.organisation_id = e.organisation_id
                LEFT JOIN evidence_scope_assessments a ON a.evidence_id = e.id
                WHERE e.interview_session_id = ? AND e.interview_mission_id = ?
                  AND e.source_type IN ('interviewee_answer', 'interviewee_answer_revision')
                ORDER BY e.created_at, e.id
                """).params(work.sessionId(), work.missionId()).query((rs, ignored) -> new Evidence(
                        rs.getObject("id", UUID.class), rs.getObject("investigation_item_id", UUID.class),
                        rs.getObject("question_id", UUID.class), rs.getObject("revises_evidence_id", UUID.class),
                        rs.getString("answer"), rs.getString("source_type"),
                        rs.getTimestamp("created_at").toInstant(), rs.getObject("participant_id", UUID.class),
                        rs.getString("intended_name"), rs.getString("scope"), rs.getBoolean("current"))).list();
        var outcomes = outcomes(work.sessionId(), work.missionId());
        var credential = runtime.issueCredential(work, "submit_findings_package");
        return new Prepared(new Context(work.id(), work.sessionId(), work.expectedRevision(),
                work.generation(), work.missionId(), mission.objective(), mission.desiredOutcome(),
                mission.completionCriteria(), mission.expectedCommitment(), sharedContext, items,
                evidence, outcomes), credential.value(), credential.expiresAt());
    }

    @Transactional
    public PackageRef complete(InterviewRuntimeRepository.Work work, FindingsExtractionRunner.Result execution) {
        tenant.select();
        var existing = packageForRun(work.id());
        if (existing != null) {
            return existing;
        }
        if (execution == null || execution.submission() == null
                || execution.runtimeVersion() == null || execution.runtimeVersion().isBlank()
                || execution.modelAttempts() < 1 || execution.modelAttempts() > 3) {
            throw new IllegalArgumentException("Runtime returned an invalid Findings Package.");
        }
        if (work.modelAttempts() + execution.modelAttempts() > 3) {
            throw new IllegalArgumentException("Runtime exceeded the Findings model attempt limit.");
        }
        runtime.requireExternalCommit(work, execution.credential(), "submit_findings_package");
        var submission = execution.submission();
        if (!work.id().equals(submission.runId()) || !work.sessionId().equals(submission.sessionId())
                || work.expectedRevision() != submission.expectedRevision() || submission.groups() == null) {
            throw new IllegalArgumentException("Runtime returned a stale Findings Package.");
        }
        var sessionValid = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM interview_sessions s
                    JOIN interview_missions m ON m.id = s.interview_mission_id
                    WHERE s.id = ? AND s.interview_mission_id = ? AND s.organisation_id = ?
                      AND s.status = 'completed' AND s.revision = ? AND m.approved_at IS NOT NULL)
                """).params(work.sessionId(), work.missionId(), work.organisationId(), work.expectedRevision())
                .query(Boolean.class).single();
        if (!sessionValid) {
            throw new IllegalArgumentException("Findings extraction scope is stale.");
        }
        var missionItems = jdbc.sql("""
                SELECT id, position, required FROM investigation_items
                WHERE interview_mission_id = ? ORDER BY position
                """).param(work.missionId()).query((rs, ignored) -> new ItemShape(
                        rs.getObject("id", UUID.class), rs.getInt("position"), rs.getBoolean("required"))).list();
        if (submission.groups().size() != missionItems.size()
                || !submission.groups().stream().map(GroupProposal::investigationItemId).toList()
                        .equals(missionItems.stream().map(ItemShape::id).toList())) {
            throw new IllegalArgumentException("Findings Package must group every Mission item in order.");
        }

        var allKnowledge = new HashMap<UUID, PreparedKnowledge>();
        var preparedGroups = new ArrayList<PreparedGroup>();
        for (int position = 0; position < missionItems.size(); position++) {
            var shape = missionItems.get(position);
            var group = submission.groups().get(position);
            if (group.knowledgeItems() == null || group.unresolvedOutcomeIds() == null
                    || group.knowledgeItems().size() > 50) {
                throw new IllegalArgumentException("Findings group is invalid.");
            }
            var expectedUnresolved = unresolvedForItem(work, shape.id());
            if (!group.unresolvedOutcomeIds().equals(expectedUnresolved.stream().map(Outcome::id).toList())) {
                throw new IllegalArgumentException("Findings Package changed unresolved outcomes.");
            }
            var knowledge = new ArrayList<PreparedKnowledge>();
            for (var proposal : group.knowledgeItems()) {
                var prepared = validateKnowledge(work, shape.id(), proposal);
                if (allKnowledge.putIfAbsent(proposal.id(), prepared) != null) {
                    throw new IllegalArgumentException("Findings Package contains duplicate Knowledge Items.");
                }
                knowledge.add(prepared);
            }
            preparedGroups.add(new PreparedGroup(shape, knowledge, expectedUnresolved));
        }
        validateLinks(allKnowledge);

        var packageId = UUID.randomUUID();
        var versionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO findings_packages (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id
                ) VALUES (?, ?, ?, ?, ?)
                """).params(packageId, work.organisationId(), work.discoveryId(),
                work.sessionId(), work.missionId()).update();
        jdbc.sql("""
                INSERT INTO findings_package_versions (
                    id, organisation_id, discovery_id, findings_package_id,
                    interview_session_id, interview_mission_id, runtime_run_id, version
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                """).params(versionId, work.organisationId(), work.discoveryId(), packageId,
                work.sessionId(), work.missionId(), work.id()).update();
        var versionIds = new HashMap<UUID, UUID>();
        for (var group : preparedGroups) {
            jdbc.sql("""
                    INSERT INTO findings_package_results (
                        organisation_id, findings_package_version_id, interview_session_id,
                        interview_mission_id, investigation_item_id, position, required
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """).params(work.organisationId(), versionId, work.sessionId(), work.missionId(),
                    group.shape().id(), group.shape().position(), group.shape().required()).update();
            for (var item : group.knowledge()) {
                var rootId = UUID.randomUUID();
                var itemVersionId = UUID.randomUUID();
                versionIds.put(item.proposal().id(), itemVersionId);
                jdbc.sql("""
                        INSERT INTO knowledge_items (
                            id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                            investigation_item_id, findings_package_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?)
                        """).params(rootId, work.organisationId(), work.discoveryId(), work.sessionId(),
                        work.missionId(), group.shape().id(), packageId).update();
                jdbc.sql("""
                        INSERT INTO knowledge_item_versions (
                            id, organisation_id, knowledge_item_id, findings_package_version_id,
                            investigation_item_id, interview_session_id, interview_mission_id,
                            version, category, claim, confirmation_state
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?)
                        """).params(itemVersionId, work.organisationId(), rootId, versionId,
                        group.shape().id(), work.sessionId(), work.missionId(), item.category(),
                        item.claim(), "assumption".equals(item.category()) ? "unconfirmed" : "unreviewed").update();
                for (int citationPosition = 0; citationPosition < item.citations().size(); citationPosition++) {
                    var citation = item.citations().get(citationPosition);
                    jdbc.sql("""
                            INSERT INTO knowledge_item_evidence_citations (
                                organisation_id, knowledge_item_version_id, investigation_item_id,
                                interview_session_id, interview_mission_id, evidence_id, position,
                                start_offset, end_offset, quotation
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """).params(work.organisationId(), itemVersionId, group.shape().id(),
                            work.sessionId(), work.missionId(), citation.proposal().evidenceId(), citationPosition,
                            citation.proposal().startOffset(), citation.proposal().endOffset(),
                            citation.proposal().quotation()).update();
                }
            }
            for (int unresolvedPosition = 0; unresolvedPosition < group.unresolved().size(); unresolvedPosition++) {
                var outcome = group.unresolved().get(unresolvedPosition);
                jdbc.sql("""
                        INSERT INTO findings_package_unresolved_outcomes (
                            organisation_id, findings_package_version_id, interview_session_id,
                            interview_mission_id, investigation_item_id, outcome_id, position, kind, summary
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """).params(work.organisationId(), versionId, work.sessionId(), work.missionId(),
                        group.shape().id(), outcome.id(), unresolvedPosition, outcome.kind(), outcome.summary()).update();
            }
        }
        for (var source : allKnowledge.values()) {
            for (var link : source.proposal().links()) {
                jdbc.sql("""
                        INSERT INTO knowledge_item_links (
                            organisation_id, findings_package_version_id,
                            source_knowledge_item_version_id, target_knowledge_item_version_id, kind
                        ) VALUES (?, ?, ?, ?, ?)
                        """).params(work.organisationId(), versionId, versionIds.get(source.proposal().id()),
                        versionIds.get(link.targetKnowledgeItemId()), link.kind()).update();
            }
        }
        runtime.settleExternal(work, execution.runtimeVersion(), execution.modelAttempts(), execution.credential());
        tenant.auditSystem("findings_extraction_completed", "findings_package", packageId);
        return new PackageRef(packageId, 1);
    }

    @Transactional
    public void retry(UUID missionId, String email) {
        var investigator = tenant.investigator(email);
        var state = ownedSession(missionId, investigator.membershipId());
        var latest = jdbc.sql("""
                SELECT status, generation FROM interview_runtime_runs
                WHERE interview_session_id = ? AND work_kind = 'findings_extraction'
                ORDER BY generation DESC LIMIT 1 FOR UPDATE
                """).param(state.sessionId()).query((rs, ignored) -> new RunStatus(
                        rs.getString("status"), rs.getInt("generation"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("No Findings extraction is available."));
        if (!"completed".equals(state.status()) || !"failed".equals(latest.status())
                || packageExists(state.sessionId())) {
            throw new IllegalArgumentException("Findings extraction cannot be retried now.");
        }
        insertRun(state, latest.generation() + 1);
        tenant.audit(investigator, "findings_extraction_retried", "interview_session", state.sessionId());
    }

    @Transactional(readOnly = true)
    public View view(UUID missionId, String email) {
        var investigator = tenant.investigator(email);
        var session = ownedSession(missionId, investigator.membershipId());
        var version = jdbc.sql("""
                SELECT v.id FROM findings_package_versions v
                WHERE v.interview_session_id = ? ORDER BY version DESC LIMIT 1
                """).param(session.sessionId()).query(UUID.class).optional();
        if (version.isEmpty()) {
            var status = jdbc.sql("""
                    SELECT status FROM interview_runtime_runs
                    WHERE interview_session_id = ? AND work_kind = 'findings_extraction'
                    ORDER BY generation DESC LIMIT 1
                    """).param(session.sessionId()).query(String.class).optional().orElse("queued");
            return new View("failed".equals(status) ? "failed" : "pending", List.of());
        }
        var groups = jdbc.sql("""
                SELECT r.investigation_item_id, i.knowledge_gap, r.required
                FROM findings_package_results r
                JOIN investigation_items i ON i.id = r.investigation_item_id
                WHERE r.findings_package_version_id = ? ORDER BY r.position
                """).param(version.get()).query((rs, ignored) -> {
                    var itemId = rs.getObject("investigation_item_id", UUID.class);
                    var knowledge = jdbc.sql("""
                            SELECT v.id, v.category, v.claim, v.confirmation_state
                            FROM knowledge_item_versions v
                            WHERE v.findings_package_version_id = ? AND v.investigation_item_id = ?
                            ORDER BY v.created_at, v.id
                            """).params(version.get(), itemId).query((knowledgeRs, ignored2) -> {
                                var itemVersionId = knowledgeRs.getObject("id", UUID.class);
                                var citations = jdbc.sql("""
                                        SELECT c.quotation, c.start_offset, c.end_offset,
                                               p.intended_name, e.source_type, e.created_at, e.answer
                                        FROM knowledge_item_evidence_citations c
                                        JOIN evidence e ON e.id = c.evidence_id
                                        JOIN discovery_participants p ON p.id = e.participant_id
                                        WHERE c.knowledge_item_version_id = ? ORDER BY c.position
                                        """).param(itemVersionId).query((citationRs, ignored3) -> new CitationView(
                                                citationRs.getString("quotation"), citationRs.getInt("start_offset"),
                                                citationRs.getInt("end_offset"), citationRs.getString("intended_name"),
                                                citationRs.getString("source_type"),
                                                citationRs.getTimestamp("created_at").toInstant(),
                                                citationRs.getString("answer"))).list();
                                return new KnowledgeView(knowledgeRs.getString("category"),
                                        knowledgeRs.getString("claim"), knowledgeRs.getString("confirmation_state"),
                                        citations);
                            }).list();
                    var unresolved = jdbc.sql("""
                            SELECT kind, summary FROM findings_package_unresolved_outcomes
                            WHERE findings_package_version_id = ? AND investigation_item_id = ? ORDER BY position
                            """).params(version.get(), itemId).query((outcomeRs, ignored2) ->
                                    new UnresolvedView(outcomeRs.getString("kind"),
                                            outcomeRs.getString("summary"))).list();
                    return new GroupView(itemId, rs.getString("knowledge_gap"),
                            rs.getBoolean("required"), knowledge, unresolved);
                }).list();
        return new View("ready", groups);
    }

    private PreparedKnowledge validateKnowledge(InterviewRuntimeRepository.Work work, UUID itemId,
            KnowledgeItemProposal proposal) {
        if (proposal == null || proposal.id() == null || !CATEGORIES.contains(proposal.category())
                || proposal.citations() == null || proposal.citations().isEmpty()
                || proposal.citations().size() > 20 || proposal.links() == null || proposal.links().size() > 20) {
            throw new IllegalArgumentException("Findings Package contains an invalid Knowledge Item.");
        }
        var claim = plain(proposal.claim(), 4_000, "Knowledge Item claim");
        var seen = new HashSet<String>();
        var citations = proposal.citations().stream().map(citation -> {
            if (citation == null || citation.evidenceId() == null || citation.quotation() == null
                    || !seen.add(citation.evidenceId() + ":" + citation.startOffset() + ":" + citation.endOffset())) {
                throw new IllegalArgumentException("Knowledge Item citation is invalid.");
            }
            var evidence = evidence(work, itemId, citation.evidenceId());
            var length = evidence.answer().codePointCount(0, evidence.answer().length());
            if (citation.startOffset() < 0 || citation.endOffset() <= citation.startOffset()
                    || citation.endOffset() > length) {
                throw new IllegalArgumentException("Knowledge Item quotation offsets are invalid.");
            }
            var start = evidence.answer().offsetByCodePoints(0, citation.startOffset());
            var end = evidence.answer().offsetByCodePoints(0, citation.endOffset());
            if (!evidence.answer().substring(start, end).equals(citation.quotation())) {
                throw new IllegalArgumentException("Knowledge Item quotation does not match Evidence.");
            }
            return new PreparedCitation(citation);
        }).toList();
        return new PreparedKnowledge(proposal, proposal.category(), claim, citations);
    }

    private EvidenceState evidence(InterviewRuntimeRepository.Work work, UUID itemId, UUID evidenceId) {
        return jdbc.sql("""
                SELECT e.answer FROM evidence e
                LEFT JOIN evidence_scope_assessments a ON a.evidence_id = e.id
                WHERE e.id = ? AND e.interview_session_id = ? AND e.interview_mission_id = ?
                  AND e.organisation_id = ? AND e.investigation_item_id = ?
                  AND e.source_type IN ('interviewee_answer', 'interviewee_answer_revision')
                  AND coalesce(a.assessment, 'in_scope') = 'in_scope'
                  AND NOT EXISTS (SELECT 1 FROM evidence child WHERE child.revises_evidence_id = e.id)
                """).params(evidenceId, work.sessionId(), work.missionId(), work.organisationId(), itemId)
                .query((rs, ignored) -> new EvidenceState(rs.getString("answer"))).optional()
                .orElseThrow(() -> new IllegalArgumentException("Knowledge Item Evidence provenance is invalid."));
    }

    private void validateLinks(Map<UUID, PreparedKnowledge> knowledge) {
        for (var source : knowledge.values()) {
            var seen = new HashSet<String>();
            for (var link : source.proposal().links()) {
                if (link == null || !Set.of("supports", "qualifies").contains(link.kind())
                        || link.targetKnowledgeItemId() == null
                        || link.targetKnowledgeItemId().equals(source.proposal().id())
                        || !knowledge.containsKey(link.targetKnowledgeItemId())
                        || !seen.add(link.kind() + ":" + link.targetKnowledgeItemId())) {
                    throw new IllegalArgumentException("Knowledge Item link is invalid.");
                }
            }
        }
    }

    private List<Outcome> outcomes(UUID sessionId, UUID missionId) {
        return jdbc.sql("""
                SELECT o.id, o.investigation_item_id, o.kind,
                       coalesce(u.explanation, u.reason, g.unresolved_subject,
                                c.unresolved_explanation, k.claim) summary
                FROM investigation_outcomes o
                LEFT JOIN unknown_outcomes u ON u.outcome_id = o.id
                LEFT JOIN ownership_gap_outcomes g ON g.outcome_id = o.id
                LEFT JOIN conflict_outcomes c ON c.outcome_id = o.id
                LEFT JOIN candidate_knowledge_claims k ON k.outcome_id = o.id
                JOIN investigation_items i ON i.id = o.investigation_item_id
                WHERE o.interview_session_id = ? AND o.interview_mission_id = ?
                ORDER BY i.position, o.created_at, o.id
                """).params(sessionId, missionId).query((rs, ignored) -> new Outcome(
                        rs.getObject("id", UUID.class), rs.getObject("investigation_item_id", UUID.class),
                        rs.getString("kind"), rs.getString("summary"),
                        Set.of("unknown", "conflict", "ownership_gap").contains(rs.getString("kind")))).list();
    }

    private List<Outcome> unresolvedForItem(InterviewRuntimeRepository.Work work, UUID itemId) {
        return outcomes(work.sessionId(), work.missionId()).stream()
                .filter(value -> value.unresolved() && value.investigationItemId().equals(itemId)).toList();
    }

    private PackageRef packageForRun(UUID runId) {
        return jdbc.sql("""
                SELECT findings_package_id, version FROM findings_package_versions WHERE runtime_run_id = ?
                """).param(runId).query((rs, ignored) -> new PackageRef(
                        rs.getObject("findings_package_id", UUID.class), rs.getInt("version"))).optional().orElse(null);
    }

    private boolean packageExists(UUID sessionId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM findings_packages WHERE interview_session_id = ?)")
                .param(sessionId).query(Boolean.class).single();
    }

    private OwnerSession ownedSession(UUID missionId, UUID ownerMembershipId) {
        return jdbc.sql("""
                SELECT s.id, s.status, s.revision, s.organisation_id, s.discovery_id, s.interview_mission_id
                FROM interview_sessions s
                JOIN discoveries d ON d.id = s.discovery_id AND d.organisation_id = s.organisation_id
                WHERE s.interview_mission_id = ? AND d.owner_membership_id = ?
                """).params(missionId, ownerMembershipId).query((rs, ignored) -> new OwnerSession(
                        rs.getObject("id", UUID.class), rs.getString("status"), rs.getInt("revision"),
                        rs.getObject("organisation_id", UUID.class), rs.getObject("discovery_id", UUID.class),
                        rs.getObject("interview_mission_id", UUID.class))).optional()
                .orElseThrow(() -> new AccessDeniedException("Findings access denied."));
    }

    private void insertRun(OwnerSession session, int generation) {
        jdbc.sql("""
                INSERT INTO interview_runtime_runs (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    work_kind, trigger, expected_revision, generation
                ) VALUES (?, ?, ?, ?, ?, 'findings_extraction', 'findings_extraction', ?, ?)
                """).params(UUID.randomUUID(), session.organisationId(), session.discoveryId(),
                session.sessionId(), session.missionId(), session.revision(), generation).update();
    }

    private static String plain(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Findings Package has invalid " + name + ".");
        }
        return value.trim();
    }

    public record Prepared(Context context, String credential, Instant credentialExpiresAt) {}
    public record Context(UUID runId, UUID sessionId, int expectedRevision, int generation,
            UUID missionVersionId, String objective, String desiredOutcome, String completionCriteria,
            String expectedCommitment, List<String> sharedContext, List<Item> investigationItems,
            List<Evidence> evidence, List<Outcome> outcomes) {}
    public record Item(UUID id, int position, String knowledgeGap, String importance,
            String priority, String relevantContext, boolean required) {}
    public record Evidence(UUID id, UUID investigationItemId, UUID questionId, UUID revisesEvidenceId,
            String content, String sourceType, Instant createdAt, UUID participantId,
            String participantName, String scope, boolean current) {}
    public record Outcome(UUID id, UUID investigationItemId, String kind, String summary, boolean unresolved) {}
    public record Submission(UUID runId, UUID sessionId, int expectedRevision, List<GroupProposal> groups) {}
    public record GroupProposal(UUID investigationItemId, List<KnowledgeItemProposal> knowledgeItems,
            List<UUID> unresolvedOutcomeIds) {}
    public record KnowledgeItemProposal(UUID id, String category, String claim,
            List<CitationProposal> citations, List<LinkProposal> links) {}
    public record CitationProposal(UUID evidenceId, int startOffset, int endOffset, String quotation) {}
    public record LinkProposal(String kind, UUID targetKnowledgeItemId) {}
    public record PackageRef(UUID id, int version) {}
    public record View(String status, List<GroupView> groups) {}
    public record GroupView(UUID investigationItemId, String knowledgeGap, boolean required,
            List<KnowledgeView> knowledgeItems, List<UnresolvedView> unresolvedOutcomes) {}
    public record KnowledgeView(String category, String claim, String confirmationState,
            List<CitationView> citations) {}
    public record CitationView(String quotation, int startOffset, int endOffset,
            String participantName, String sourceType, Instant createdAt, String answerContext) {}
    public record UnresolvedView(String kind, String summary) {}

    private record Mission(String objective, String desiredOutcome, String completionCriteria,
            String expectedCommitment) {}
    private record ItemShape(UUID id, int position, boolean required) {}
    private record EvidenceState(String answer) {}
    private record PreparedCitation(CitationProposal proposal) {}
    private record PreparedKnowledge(KnowledgeItemProposal proposal, String category,
            String claim, List<PreparedCitation> citations) {}
    private record PreparedGroup(ItemShape shape, List<PreparedKnowledge> knowledge,
            List<Outcome> unresolved) {}
    private record OwnerSession(UUID sessionId, String status, int revision, UUID organisationId,
            UUID discoveryId, UUID missionId) {}
    private record RunStatus(String status, int generation) {}
}
