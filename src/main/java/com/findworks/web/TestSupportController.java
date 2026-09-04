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
            db.sql("insert into mission_versions(mission_id,organization_id,version,state,objective,desired_outcome,interviewee_role,interviewee_relevance,expected_minutes,data_use,created_by,approved_by,approved_at) values(:mission,:organization,1,'APPROVED','Understand Ledgerling reconciliation','Know which mismatches need human judgement.','Accountant','Performs reconciliation every month.',20,'Reviewed findings and bounded source excerpts may be exported; later deletion cannot recall those copies.',:owner,:owner,now())")
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
                "/mission/intended_interviewee_relevance", "/mission/expected_commitment_minutes",
                "/mission/data_use_summary", "/mission/investigation_items/0", "/mission/shared_context/0",
                "/mission/boundaries/0", "/mission/prohibited_topics/0", "/mission/opening_questions/0",
                "/mission/completion_criteria/0")) {
            db.sql("insert into mission_origins values(:mission,1,:pointer,'INVESTIGATOR_STATEMENT',:owner,now())")
                    .param("mission", fixture.get("mission_id")).param("pointer", pointer).param("owner", OWNER).update();
        }
        return fixture;
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

    @PostMapping("/test/sessions/{id}/near-limit")
    @ResponseBody
    Map<String, Object> nearLimit(@PathVariable UUID id) {
        db.sql("update interview_sessions set started_at=now()-interval '19 minutes' where id=:id")
                .param("id", id).update();
        return Map.of("session_id", id);
    }
}
