package com.findworks.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class RuntimeTurnContext {
    private final JdbcClient db;

    RuntimeTurnContext(JdbcClient db) {
        this.db = db;
    }

    public Map<String, Object> project(UUID runId, UUID sessionId, int missionVersion, int expectedRevision) {
        var result = new LinkedHashMap<String, Object>();
        result.put("run_id", runId);
        result.put("session_id", sessionId);
        result.put("mission_version", missionVersion);
        result.put("expected_revision", expectedRevision);
        result.put("mission", db.sql("""
                select mv.objective,mv.desired_outcome,mv.expected_minutes,mv.data_use
                from interview_sessions s join mission_versions mv on mv.mission_id=s.mission_id and mv.version=s.mission_version
                where s.id=:session
                """).param("session", sessionId).query((rs, row) -> Map.of(
                "objective", rs.getString(1), "desired_outcome", rs.getString(2),
                "expected_minutes", rs.getInt(3), "data_use", rs.getString(4))).single());
        result.put("shared_mission_entries", db.sql("""
                select e.kind,e.position,e.value,e.detail from interview_sessions s join mission_entries e on e.mission_id=s.mission_id and e.version=s.mission_version
                where s.id=:session order by e.kind,e.position
                """).param("session", sessionId).query((rs, row) -> {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("kind", rs.getString(1));
            entry.put("position", rs.getInt(2));
            entry.put("value", rs.getString(3));
            if (rs.getString(4) != null) entry.put("detail", rs.getString(4));
            return entry;
        }).list());
        var outcomesByResult = new LinkedHashMap<UUID, List<Map<String, Object>>>();
        var storedOutcomes = db.sql("""
                select o.result_id,o.id,o.coverage,o.category,o.summary,
                       coalesce(array_agg(oe.evidence_id order by oe.evidence_id) filter (where oe.evidence_id is not null),'{}')
                from result_outcomes o left join result_outcome_evidence oe on oe.outcome_id=o.id
                where o.result_id in (select id from investigation_results where session_id=:session)
                group by o.result_id,o.id,o.coverage,o.category,o.summary,o.created_at
                order by o.created_at,o.id
                """).param("session", sessionId).query((rs, row) -> new StoredOutcome(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                rs.getString(5), Arrays.asList((UUID[]) rs.getArray(6).getArray()))).list();
        for (var stored : storedOutcomes) {
            var outcome = new LinkedHashMap<String, Object>();
            outcome.put("outcome_id", stored.id());
            outcome.put("coverage", stored.coverage());
            if (stored.category() != null) outcome.put("category", stored.category());
            outcome.put("summary", stored.summary());
            outcome.put("evidence_ids", stored.evidenceIds());
            outcomesByResult.computeIfAbsent(stored.resultId(), ignored -> new ArrayList<>()).add(outcome);
        }
        result.put("investigation_results", db.sql("""
                select r.id,i.gap,i.why,i.priority,i.required,i.context,i.sufficient,r.coverage
                from investigation_results r join investigation_items i on i.id=r.item_id where r.session_id=:session order by i.position
                """).param("session", sessionId).query((rs, row) -> {
            var resultView = new LinkedHashMap<String, Object>();
            var resultId = rs.getObject(1, UUID.class);
            resultView.put("result_id", resultId);
            resultView.put("knowledge_gap", rs.getString(2));
            resultView.put("why_it_matters", rs.getString(3));
            resultView.put("priority", rs.getString(4));
            resultView.put("required", rs.getBoolean(5));
            resultView.put("relevant_context", rs.getString(6));
            resultView.put("sufficient_evidence", rs.getString(7));
            resultView.put("coverage", rs.getString(8));
            resultView.put("current_outcomes", outcomesByResult.getOrDefault(resultId, List.of()));
            return resultView;
        }).list());
        result.put("questions_and_evidence", db.sql("""
                select q.id,q.sequence,q.text,e.id,e.source_type,e.exact_text,e.option_id,e.option_label,e.explanation
                from questions q left join evidence e on e.question_id=q.id where q.session_id=:session order by q.sequence,e.created_at,e.id
                """).param("session", sessionId).query((rs, row) -> {
            var turn = new LinkedHashMap<String, Object>();
            turn.put("question_id", rs.getObject(1));
            turn.put("sequence", rs.getInt(2));
            turn.put("question", rs.getString(3));
            if (rs.getObject(4) != null) {
                turn.put("evidence_id", rs.getObject(4));
                turn.put("source_type", rs.getString(5));
                turn.put("exact_text", rs.getString(6));
                if (rs.getString(7) != null) turn.put("option_id", rs.getString(7));
                if (rs.getString(8) != null) turn.put("option_label", rs.getString(8));
                if (rs.getString(9) != null) turn.put("explanation", rs.getString(9));
            }
            return turn;
        }).list());
        return result;
    }

    private record StoredOutcome(UUID resultId, UUID id, String coverage, String category, String summary,
                                 List<UUID> evidenceIds) {}
}
