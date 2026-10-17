package com.findworks.web;

import java.util.Map;
import java.util.UUID;

import com.findworks.platform.Ids;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
@ConditionalOnProperty(name = "findworks.test-support", havingValue = "true")
class TestSupportController {
    private static final UUID ORGANIZATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OWNER = UUID.fromString("f1111111-1111-1111-1111-111111111111");
    private static final java.util.List<String> LONG_QUESTIONS = java.util.List.of(
            "Walk me through the last mismatch you resolved.",
            "What first told you the imported amount needed attention?",
            "You called that the day-end check. What happens during that check?",
            "Who normally picks up a mismatch after the day-end check?",
            "What information does Priya need before she can decide?",
            "When the cashbook and source ledger disagree, which one does she check first?",
            "What makes a mismatch routine rather than exceptional?",
            "What does approval look like for an exceptional mismatch?",
            "You mentioned Gareth’s sign-off. When is that actually required?",
            "What gets recorded after Gareth approves a correction?",
            "How does the next person know the correction is complete?",
            "What happens when the supporting reference is missing?",
            "How long do you wait before asking the finance analyst for it?",
            "Who owns the case while you are waiting?",
            "What changes when the missing reference arrives?",
            "Can you describe a recent case that did not follow the usual path?",
            "What made that case different from the routine ones?",
            "How did the team record the exception without losing the original amount?",
            "Earlier you said every mismatch needed approval. Is that still accurate?",
            "What is the corrected approval rule in your own words?",
            "What should a new colleague remember about the day-end check?",
            "Is there anything important about mismatch decisions that we have missed?");
    private static final String LONG_REVISION = "I need to correct that: Gareth does not approve every mismatch. He signs off only exceptional corrections or routine corrections above £500.";
    private static final java.util.List<String> LONG_ANSWERS = java.util.List.of(
            "Yesterday an imported card payment was £18.40 higher than the cashbook entry, so I opened the source ledger and checked the reference before changing anything.",
            "The variance marker appeared after import, but the amount alone was not enough; the missing supplier reference was what needed attention.",
            "We call it the day-end check. Priya compares each unmatched entry with the cashbook, then opens the source document only when those totals differ.",
            "Priya, our finance analyst, picks it up first. I keep ownership only if it is a missing-document problem rather than a judgement call.",
            "She needs the imported amount, cashbook line, supplier reference, date, and any note from the person who entered it.",
            "She checks the cashbook first because it is the team’s working record, then uses the source ledger to understand why it differs.",
            "A routine mismatch has one clear reference and an obvious transposition or duplicate. Anything with competing references or a policy exception is exceptional.",
            "Priya writes the proposed correction and the reason, then Gareth reviews the source material and records approve or return.",
            "Gareth signs off the correction after Priya records the proposed amount and supporting reference.",
            "The entry keeps the original amount, corrected amount, reason, source reference, Priya’s name, Gareth’s decision when needed, and the decision time.",
            "The matched total appears in the day-end summary and the item changes from amber to settled, with the decision note still available.",
            "We leave the amount untouched, mark the reference as requested, and ask the finance analyst who supplied the batch.",
            "We normally wait until the next working morning. Urgent month-end items are chased after two hours instead.",
            "Priya still owns the mismatch while waiting; the supplier team only owns providing the missing reference.",
            "Priya reruns the comparison, attaches the reference, and either settles the routine mismatch or sends an exceptional one for sign-off.",
            "Last month two source documents used the same reference. We kept both originals and asked the treasury lead which payment was authoritative.",
            "There was no safe way to choose from the records we had, so it was an ownership gap rather than a normal correction.",
            "We added a new decision entry linked to both originals. Nobody edited or deleted the first imported amount.",
            "No. My first wording was too broad; routine mismatches at or below £500 do not need Gareth’s approval when the reference is clear.",
            "Priya can settle a clear routine mismatch up to £500. Gareth approves exceptional cases and any routine correction above that amount.",
            "Do not treat the amber marker as proof. Finish the day-end check against the cashbook and keep the original Evidence beside any correction.");
    private final JdbcClient db;
    private final TransactionTemplate transactions;
    private final String baseUrl;

    TestSupportController(JdbcClient db, TransactionTemplate transactions, @Value("${findworks.base-url}") String baseUrl) {
        this.db = db; this.transactions = transactions; this.baseUrl = baseUrl;
    }

    @PostMapping("/test/fixtures/approved-mission")
    @ResponseBody
    Map<String, Object> approvedMission(@RequestParam(defaultValue = "playwright@example.com") String email) {
        return transactions.execute(status -> {
            var discovery = Ids.id(); var mission = Ids.id(); var item = Ids.id(); var participant = Ids.id(); var session = Ids.id(); var invitation = Ids.id(); var raw = Ids.token();
            db.sql("insert into discoveries(id,organization_id,owner_membership_id,title,objective) values(:id,:organization,:owner,'Ledgerling reconciliation discovery','Learn how accountants reconcile imported transactions.')")
                    .param("id", discovery).param("organization", ORGANIZATION).param("owner", OWNER).update();
            db.sql("insert into missions values(:id,:organization,:discovery,'Ledgerling reconciliation',1,1,now())")
                    .param("id", mission).param("organization", ORGANIZATION).param("discovery", discovery).update();
            db.sql("insert into mission_versions(mission_id,organization_id,version,state,objective,desired_outcome,interviewee_role,interviewee_relevance,data_use,created_by,approved_by,approved_at) values(:mission,:organization,1,'APPROVED','Understand Ledgerling reconciliation','Know which mismatches need human judgement.','Accountant','Performs reconciliation every month.','Reviewed findings and bounded source excerpts may be exported; later deletion cannot recall those copies.',:owner,:owner,now())")
                    .param("mission", mission).param("organization", ORGANIZATION).param("owner", OWNER).update();
            db.sql("insert into mission_entries values(:mission,1,'SHARED_CONTEXT',0,'Ledgerling imports bank transactions.',null),(:mission,1,'BOUNDARY',0,'Discuss reconciliation workflow only.',null),(:mission,1,'PROHIBITED_TOPIC',0,'Customer-identifying data',null),(:mission,1,'OPENING_QUESTION',0,'Walk me through the last mismatch you resolved.',null),(:mission,1,'COMPLETION_CRITERION',0,'Every required area has an explicit outcome.',null)")
                    .param("mission", mission).update();
            db.sql("insert into investigation_items values(:item,:mission,1,0,'Which mismatches require human judgement?','Automation must stop safely.','HIGH',true,'Ledgerling imports bank transactions.','Examples of mismatch types')")
                    .param("item", item).param("mission", mission).update();
            db.sql("insert into participants values(:participant,:organization,:discovery,'Clare',:email)")
                    .param("participant", participant).param("organization", ORGANIZATION).param("discovery", discovery).param("email", email).update();
            db.sql("insert into interview_sessions(id,organization_id,discovery_id,mission_id,mission_version,participant_id) values(:session,:organization,:discovery,:mission,1,:participant)")
                    .param("session", session).param("organization", ORGANIZATION).param("discovery", discovery).param("mission", mission).param("participant", participant).update();
            db.sql("insert into invitations values(:invitation,:organization,:session,:email,:hash,'DELIVERED',now()+interval '7 days',now(),null,null)")
                    .param("invitation", invitation).param("organization", ORGANIZATION).param("session", session).param("email", email).param("hash", Ids.sha(raw)).update();
            return Map.of("discovery_id", discovery, "mission_id", mission, "session_id", session,
                    "invitation_id", invitation, "invitation_url", baseUrl + "/invite/" + raw,
                    "review_url", baseUrl + "/app/missions/" + mission + "/review");
        });
    }

    @PostMapping("/test/fixtures/draft-mission")
    @ResponseBody
    Map<String, Object> draftMission(@RequestParam(defaultValue = "playwright@example.com") String email) {
        var fixture = approvedMission(email);
        db.sql("update missions set approved_version=null where id=:id").param("id", fixture.get("mission_id")).update();
        db.sql("update mission_versions set state='DRAFT',approved_by=null,approved_at=null where mission_id=:id")
                .param("id", fixture.get("mission_id")).update();
        for (var pointer : java.util.List.of(
                "/mission/objective", "/mission/desired_outcome", "/mission/intended_interviewee_role",
                "/mission/intended_interviewee_relevance", "/mission/data_use_summary",
                "/mission/investigation_items/0", "/mission/shared_context/0",
                "/mission/boundaries/0", "/mission/prohibited_topics/0", "/mission/opening_questions/0",
                "/mission/completion_criteria/0")) {
            db.sql("insert into mission_origins values(:mission,1,:pointer,'INVESTIGATOR_STATEMENT',:owner,now())")
                    .param("mission", fixture.get("mission_id")).param("pointer", pointer).param("owner", OWNER).update();
        }
        return fixture;
    }

    @PostMapping("/test/interviews/{sessionId}/long-conversation")
    @ResponseBody
    Map<String, Object> longConversation(@PathVariable UUID sessionId) {
        return transactions.execute(status -> {
            var session = db.sql("select organization_id,participant_id from interview_sessions where id=:session and state='ACTIVE'")
                    .param("session", sessionId).query((rs, row) -> Map.of(
                            "organization", rs.getObject(1, UUID.class), "participant", rs.getObject(2, UUID.class)))
                    .single();
            var first = db.sql("select id,item_id from questions where session_id=:session and sequence=1")
                    .param("session", sessionId).query((rs, row) -> Map.of(
                            "question", rs.getObject(1, UUID.class), "item", rs.getObject(2, UUID.class))).single();
            if (db.sql("select count(*) from evidence where session_id=:session").param("session", sessionId).query(Long.class).single() != 0) {
                throw new IllegalStateException("Long-conversation fixture requires a new interview");
            }
            var questionIds = new java.util.ArrayList<UUID>();
            var evidenceIds = new java.util.ArrayList<UUID>();
            questionIds.add((UUID) first.get("question"));
            db.sql("update questions set active=false where id=:question").param("question", first.get("question")).update();
            var firstEvidence = Ids.id();
            evidenceIds.add(firstEvidence);
            db.sql("insert into evidence(id,organization_id,session_id,question_id,participant_id,source_type,exact_text) values(:id,:organization,:session,:question,:participant,'ANSWER',:text)")
                    .param("id", firstEvidence).param("organization", session.get("organization")).param("session", sessionId)
                    .param("question", first.get("question")).param("participant", session.get("participant"))
                    .param("text", LONG_ANSWERS.getFirst()).update();
            for (int index = 1; index < LONG_ANSWERS.size(); index++) {
                var question = Ids.id();
                var evidence = Ids.id();
                questionIds.add(question);
                evidenceIds.add(evidence);
                db.sql("insert into questions(id,organization_id,session_id,item_id,sequence,text,active) values(:id,:organization,:session,:item,:sequence,:text,false)")
                        .param("id", question).param("organization", session.get("organization")).param("session", sessionId)
                        .param("item", first.get("item")).param("sequence", index + 1).param("text", LONG_QUESTIONS.get(index)).update();
                db.sql("insert into evidence(id,organization_id,session_id,question_id,participant_id,source_type,exact_text) values(:id,:organization,:session,:question,:participant,'ANSWER',:text)")
                        .param("id", evidence).param("organization", session.get("organization")).param("session", sessionId)
                        .param("question", question).param("participant", session.get("participant"))
                        .param("text", LONG_ANSWERS.get(index)).update();
            }
            db.sql("insert into evidence(id,organization_id,session_id,question_id,participant_id,source_type,exact_text,revises) values(:id,:organization,:session,:question,:participant,'REVISION',:text,:revises)")
                    .param("id", Ids.id()).param("organization", session.get("organization")).param("session", sessionId)
                    .param("question", questionIds.get(8)).param("participant", session.get("participant"))
                    .param("text", LONG_REVISION).param("revises", evidenceIds.get(8)).update();
            db.sql("insert into questions(id,organization_id,session_id,item_id,sequence,text,active) values(:id,:organization,:session,:item,:sequence,:text,true)")
                    .param("id", Ids.id()).param("organization", session.get("organization")).param("session", sessionId)
                    .param("item", first.get("item")).param("sequence", LONG_ANSWERS.size() + 1)
                    .param("text", LONG_QUESTIONS.getLast()).update();
            db.sql("update investigation_results set coverage='EXPLORING' where session_id=:session")
                    .param("session", sessionId).update();
            db.sql("update interview_sessions set revision=:revision where id=:session")
                    .param("revision", LONG_ANSWERS.size() + 1).param("session", sessionId).update();
            return Map.of("session_id", sessionId, "answered_turns", LONG_ANSWERS.size());
        });
    }

    @PostMapping("/test/interviews/{sessionId}/state")
    @ResponseBody
    Map<String, Object> interviewState(@PathVariable UUID sessionId, @RequestParam String state) {
        if (!java.util.Set.of("PAUSED", "RUNTIME_FAILED", "AWAITING_CONFIRMATION", "COMPLETED", "UNAVAILABLE").contains(state)) {
            throw new IllegalArgumentException("Unsupported test Interview Session state");
        }
        var changed = db.sql("""
                update interview_sessions set state=:state,
                    completed_at=case when :state='COMPLETED' then now() else null end,
                    extraction_state=case when :state='COMPLETED' then 'READY' else extraction_state end
                where id=:session
                """)
                .param("state", state).param("session", sessionId).update();
        if (changed != 1) throw new IllegalArgumentException("Unknown Interview Session");
        return Map.of("session_id", sessionId, "state", state);
    }

    @PostMapping("/test/interviews/{sessionId}/revoke-access")
    @ResponseBody
    Map<String, Object> revokeInterviewAccess(@PathVariable UUID sessionId) {
        db.sql("update access_grants set revoked_at=now() where session_id=:session")
                .param("session", sessionId).update();
        db.sql("update interview_account_access set revoked_at=now() where session_id=:session")
                .param("session", sessionId).update();
        return Map.of("session_id", sessionId, "revoked", true);
    }

    @PostMapping("/test/fixtures/non-owner")
    @ResponseBody
    void nonOwner() {
        db.sql("insert into users(id,email,display_name,password_hash,email_verified,active) values('abababab-abab-abab-abab-abababababab','reviewer@example.com','Reviewer','{noop}findworks',true,true) on conflict do nothing").update();
        db.sql("insert into memberships(id,organization_id,user_id,role,active) values('fbfbfbfb-bfbf-bfbf-bfbf-bfbfbfbfbfbf',:organization,'abababab-abab-abab-abab-abababababab','INVESTIGATOR',true) on conflict do nothing")
                .param("organization", ORGANIZATION).update();
    }

    @PostMapping("/test/faults/{kind}")
    @ResponseBody
    Map<String, Object> fault(@PathVariable String kind, @RequestParam int remaining) {
        if (!java.util.Set.of("runtime", "runtime_death", "extraction", "email").contains(kind) || remaining < 0 || remaining > 10) throw new IllegalArgumentException();
        db.sql("update fault_controls set remaining=:remaining where kind=:kind").param("remaining", remaining).param("kind", kind).update();
        return Map.of("kind", kind, "remaining", remaining);
    }

    @PostMapping("/test/invitations/{id}/expire")
    @ResponseBody
    Map<String, Object> expire(@PathVariable UUID id) {
        db.sql("update invitations set expires_at=now()-interval '1 second' where id=:id").param("id", id).update();
        return Map.of("expired", id);
    }

    @PostMapping("/test/discoveries/{id}/retention-warning")
    @ResponseBody
    Map<String, Object> warning(@PathVariable UUID id) {
        db.sql("update discoveries set retention_due_at=now()+interval '13 days' where id=:id").param("id", id).update();
        return Map.of("discovery_id", id);
    }
}
