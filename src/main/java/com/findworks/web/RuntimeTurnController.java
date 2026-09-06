package com.findworks.web;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.findworks.platform.Ids;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class RuntimeTurnController {
    private final JdbcClient db;
    private final TransactionTemplate transactions;

    RuntimeTurnController(JdbcClient db, TransactionTemplate transactions) {
        this.db = db;
        this.transactions = transactions;
    }

    @PostMapping("/internal/runtime/turn")
    @ResponseBody
    ResponseEntity<?> submit(@RequestHeader(value = "Authorization", required = false) String authorization,
                             @RequestBody Turn turn) {
        if (authorization == null || !authorization.startsWith("Bearer ")) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        try {
            var event = transactions.execute(status -> commit(Ids.sha(authorization.substring(7)), turn));
            return ResponseEntity.ok(Map.of("event", event));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest().body(Map.of("code", exception.getMessage()));
        } catch (SecurityException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
    }

    private String commit(String tokenHash, Turn turn) {
        var authorised = db.sql("""
                select r.id,r.organization_id,r.session_id,r.expected_revision,r.stable_event,c.used_at
                from runtime_turn_credentials c join runtime_runs r on r.id=c.run_id
                where c.token_hash=:hash and c.expires_at>now() and r.id=:run for update of c,r
                """).param("hash", tokenHash).param("run", turn.runId())
                .query((rs, row) -> new AuthorisedRun(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getInt(4), rs.getString(5), rs.getObject(6) != null))
                .optional().orElseThrow(SecurityException::new);
        if (authorised.used()) return authorised.stableEvent();
        if (authorised.expectedRevision() != turn.expectedRevision()) throw new IllegalArgumentException("stale_revision");
        var revision = db.sql("select revision from interview_sessions where id=:session for update")
                .param("session", authorised.sessionId()).query(Integer.class).single();
        if (revision != authorised.expectedRevision()) throw new IllegalArgumentException("stale_revision");
        var outcomes = turn.outcomes() == null ? List.<Outcome>of() : turn.outcomes();
        for (var outcome : outcomes) {
            if (!Set.of("UNKNOWN", "CONFLICT", "OWNERSHIP_GAP").contains(outcome.coverage())
                    || outcome.evidenceIds() == null || outcome.evidenceIds().isEmpty()) continue;
            var directlyExplored = db.sql("""
                    select count(*) from evidence e join questions q on q.id=e.question_id
                    join investigation_results r on r.item_id=q.item_id and r.session_id=e.session_id
                    where r.id=:result and r.session_id=:session and e.id in (:evidence)
                    """).param("result", outcome.resultId()).param("session", authorised.sessionId())
                    .param("evidence", outcome.evidenceIds().stream().distinct().toList()).query(Long.class).single();
            if (directlyExplored == 0) throw new IllegalArgumentException("unsubstantiated_unresolved_outcome");
        }
        for (var outcome : outcomes) {
            if (!Set.of("SUPPORTED", "UNKNOWN", "CONFLICT", "ASSUMPTION", "OWNERSHIP_GAP").contains(outcome.coverage())) {
                throw new IllegalArgumentException("invalid_outcome");
            }
            if (outcome.summary() == null || outcome.summary().isBlank() || outcome.summary().length() > 2_000
                    || outcome.evidenceIds() == null || outcome.evidenceIds().isEmpty()) {
                throw new IllegalArgumentException("invalid_outcome_evidence");
            }
            var category = outcome.coverage().equals("ASSUMPTION") ? "ASSUMPTION"
                    : outcome.category() == null ? "FACT" : outcome.category();
            if ((outcome.coverage().equals("SUPPORTED") || outcome.coverage().equals("ASSUMPTION"))
                    && !Set.of("FACT", "RULE", "DECISION", "TERM", "EXCEPTION", "ASSUMPTION").contains(category)) {
                throw new IllegalArgumentException("invalid_knowledge_category");
            }
            var evidenceIds = outcome.evidenceIds().stream().distinct().toList();
            var evidenceCount = db.sql("select count(*) from evidence where session_id=:session and id in (:evidence)")
                    .param("session", authorised.sessionId()).param("evidence", evidenceIds).query(Long.class).single();
            if (evidenceCount != evidenceIds.size()) throw new IllegalArgumentException("invalid_outcome_evidence");
            var changed = db.sql("update investigation_results set coverage=case when coverage in ('SUPPORTED','ASSUMPTION') then coverage else :coverage end where id=:item and session_id=:session")
                    .param("coverage", outcome.coverage()).param("item", outcome.resultId()).param("session", authorised.sessionId()).update();
            if (changed != 1) throw new IllegalArgumentException("invalid_outcome_scope");
            var summary = outcome.summary().trim();
            var existingOutcome = db.sql("""
                    select id from result_outcomes
                    where result_id=:result and coverage=:coverage and category=:category and summary=:summary
                    order by created_at,id limit 1
                    """).param("result", outcome.resultId()).param("coverage", outcome.coverage())
                    .param("category", category).param("summary", summary).query(UUID.class).optional();
            var outcomeId = existingOutcome.orElseGet(Ids::id);
            if (existingOutcome.isEmpty()) {
                db.sql("insert into result_outcomes(id,result_id,coverage,category,summary) values(:id,:result,:coverage,:category,:summary)")
                        .param("id", outcomeId).param("result", outcome.resultId()).param("coverage", outcome.coverage())
                        .param("category", category).param("summary", summary).update();
            }
            for (var evidenceId : evidenceIds) {
                db.sql("insert into result_evidence values(:result,:evidence) on conflict do nothing")
                        .param("result", outcome.resultId()).param("evidence", evidenceId).update();
                db.sql("insert into result_outcome_evidence values(:outcome,:evidence) on conflict do nothing")
                        .param("outcome", outcomeId).param("evidence", evidenceId).update();
            }
            if (Set.of("UNKNOWN", "CONFLICT", "OWNERSHIP_GAP").contains(outcome.coverage())) {
                var existingUnresolved = db.sql("""
                        select id from unresolved
                        where result_id=:result and kind=:kind and summary=:summary and reason=:reason
                        order by id limit 1
                        """).param("result", outcome.resultId()).param("kind", outcome.coverage())
                        .param("summary", summary).param("reason", summary).query(UUID.class).optional();
                var unresolvedId = existingUnresolved.orElseGet(Ids::id);
                if (existingUnresolved.isEmpty()) {
                    db.sql("insert into unresolved values(:id,:result,:evidence,:kind,:summary,:reason,null,false)")
                            .param("id", unresolvedId).param("result", outcome.resultId()).param("evidence", evidenceIds.getFirst())
                            .param("kind", outcome.coverage()).param("summary", summary).param("reason", summary).update();
                }
                for (var evidenceId : evidenceIds) db.sql("insert into unresolved_evidence values(:unresolved,:evidence) on conflict do nothing")
                        .param("unresolved", unresolvedId).param("evidence", evidenceId).update();
            }
        }
        var action = turn.nextAction();
        if (action == null || !Set.of("ASK_QUESTION", "PROPOSE_COMPLETION").contains(action.type())) {
            throw new IllegalArgumentException("exactly_one_next_action_required");
        }
        String event;
        if (action.type().equals("ASK_QUESTION")) {
            if (action.text() == null || action.text().isBlank() || action.text().length() > 2_000) throw new IllegalArgumentException("invalid_question");
            var resultId = action.resultId();
            var itemId = db.sql("select item_id from investigation_results where id=:result and session_id=:session")
                    .param("result", resultId).param("session", authorised.sessionId()).query(UUID.class).optional()
                    .orElseThrow(() -> new IllegalArgumentException("invalid_question_scope"));
            var mode = action.responseMode() == null ? "FREE_TEXT" : action.responseMode();
            if (!Set.of("FREE_TEXT", "YES_NO", "YES_NO_PARTLY", "PARAPHRASE", "CHOICE").contains(mode)) throw new IllegalArgumentException("invalid_response_mode");
            var suppliedOptions = action.options() == null ? List.<Option>of() : action.options();
            var options = switch (mode) {
                case "YES_NO" -> List.of(new Option("yes", "Yes"), new Option("no", "No"));
                case "YES_NO_PARTLY" -> List.of(new Option("yes", "Yes"), new Option("no", "No"),
                        new Option("partly", "Partly"));
                case "PARAPHRASE" -> List.of(new Option("confirm", "Yes, that's right"),
                        new Option("correct", "No, it needs correction"));
                case "CHOICE" -> suppliedOptions;
                default -> List.<Option>of();
            };
            if (mode.equals("CHOICE") && (options.size() < 2 || options.size() > 5)) throw new IllegalArgumentException("invalid_options");
            if (options.stream().anyMatch(option -> option.id() == null || option.id().isBlank() || option.id().length() > 80
                    || option.label() == null || option.label().isBlank() || option.label().length() > 200)
                    || options.stream().map(Option::id).distinct().count() != options.size()) {
                throw new IllegalArgumentException("invalid_options");
            }
            var questionId = Ids.id();
            var sequence = db.sql("select coalesce(max(sequence),0)+1 from questions where session_id=:session")
                    .param("session", authorised.sessionId()).query(Integer.class).single();
            db.sql("insert into questions(id,organization_id,session_id,item_id,sequence,text,response_mode) values(:id,:organization,:session,:item,:sequence,:text,:mode)")
                    .param("id", questionId).param("organization", authorised.organizationId()).param("session", authorised.sessionId())
                    .param("item", itemId).param("sequence", sequence).param("text", action.text()).param("mode", mode).update();
            for (int index = 0; index < options.size(); index++) {
                var option = options.get(index);
                db.sql("insert into question_options values(:question,:id,:position,:label)").param("question", questionId)
                        .param("id", option.id()).param("position", index).param("label", option.label()).update();
            }
            event = "question_ready";
        } else {
            var incomplete = db.sql("""
                    select count(*) from investigation_results r join investigation_items i on i.id=r.item_id
                    where r.session_id=:session and i.required and r.coverage in ('UNADDRESSED','EXPLORING','PARTIAL')
                    """).param("session", authorised.sessionId()).query(Long.class).single();
            if (incomplete > 0) throw new IllegalArgumentException("premature_completion");
            db.sql("update interview_sessions set state='AWAITING_CONFIRMATION' where id=:session")
                    .param("session", authorised.sessionId()).update();
            event = "completion_confirmation_ready";
        }
        db.sql("update interview_sessions set revision=revision+1 where id=:session").param("session", authorised.sessionId()).update();
        db.sql("update runtime_runs set state='COMPLETED',lease_until=null,stable_event=:event,error_code=null where id=:run")
                .param("event", event).param("run", authorised.id()).update();
        db.sql("update runtime_checkpoints set state='COMMITTED' where run_id=:run").param("run", authorised.id()).update();
        db.sql("update runtime_turn_credentials set used_at=now() where token_hash=:hash").param("hash", tokenHash).update();
        return event;
    }

    public record Turn(UUID runId, int expectedRevision, List<Outcome> outcomes, NextAction nextAction) {}
    public record Outcome(UUID resultId, String coverage, String category, List<UUID> evidenceIds, String summary) {}
    public record NextAction(String type, UUID resultId, String text, String responseMode, List<Option> options) {}
    public record Option(String id, String label) {}
    private record AuthorisedRun(UUID id, UUID organizationId, UUID sessionId, int expectedRevision,
                                 String stableEvent, boolean used) {}
}
