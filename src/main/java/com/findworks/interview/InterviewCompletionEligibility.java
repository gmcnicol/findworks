package com.findworks.interview;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
final class InterviewCompletionEligibility {

    private final JdbcClient jdbc;

    InterviewCompletionEligibility(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void requireEligible(UUID sessionId, UUID missionId, UUID organisationId) {
        var missing = jdbc.sql("""
                SELECT count(*) FROM investigation_items i
                WHERE i.interview_mission_id = ? AND i.organisation_id = ? AND i.required
                  AND NOT EXISTS (
                    SELECT 1 FROM investigation_results r
                    WHERE r.interview_session_id = ?
                      AND r.interview_mission_id = i.interview_mission_id
                      AND r.investigation_item_id = i.id
                      AND r.organisation_id = i.organisation_id
                      AND r.status = 'explicit_outcome'
                      AND EXISTS (
                        SELECT 1 FROM investigation_outcomes o
                        LEFT JOIN candidate_knowledge_claims c ON c.outcome_id = o.id
                        WHERE o.investigation_result_id = r.id
                          AND (o.kind IN ('unknown', 'conflict', 'ownership_gap')
                            OR (o.kind = 'supported_knowledge' AND c.confirmation_state = 'confirmed'))
                          AND EXISTS (
                            SELECT 1 FROM investigation_outcome_evidence oe
                            JOIN evidence e ON e.id = oe.evidence_id
                            WHERE oe.outcome_id = o.id
                              AND NOT EXISTS (
                                SELECT 1 FROM evidence revision
                                WHERE revision.revises_evidence_id = e.id
                              )
                          )
                      )
                  )
                """).params(missionId, organisationId, sessionId).query(Integer.class).single();
        if (missing != 0) {
            throw new IllegalArgumentException("Every required Investigation Item needs an explicit outcome.");
        }
    }

    List<Unresolved> unresolved(UUID sessionId, UUID missionId, UUID organisationId) {
        return jdbc.sql("""
                SELECT o.id, o.investigation_item_id, o.kind
                FROM investigation_outcomes o
                JOIN investigation_items i ON i.id = o.investigation_item_id
                    AND i.interview_mission_id = o.interview_mission_id
                    AND i.organisation_id = o.organisation_id AND i.required
                JOIN investigation_results r ON r.id = o.investigation_result_id
                    AND r.status = 'explicit_outcome'
                WHERE o.interview_session_id = ? AND o.interview_mission_id = ?
                  AND o.organisation_id = ?
                  AND o.kind IN ('unknown', 'conflict', 'ownership_gap')
                  AND EXISTS (
                    SELECT 1 FROM investigation_outcome_evidence oe
                    JOIN evidence e ON e.id = oe.evidence_id
                    WHERE oe.outcome_id = o.id
                      AND NOT EXISTS (
                        SELECT 1 FROM evidence revision WHERE revision.revises_evidence_id = e.id
                      )
                  )
                ORDER BY i.position, o.created_at, o.id
                """).params(sessionId, missionId, organisationId)
                .query((rs, ignored) -> new Unresolved(
                        rs.getObject("id", UUID.class),
                        rs.getObject("investigation_item_id", UUID.class),
                        rs.getString("kind"))).list();
    }

    record Unresolved(UUID outcomeId, UUID investigationItemId, String kind) {}
}
