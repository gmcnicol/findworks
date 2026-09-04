package com.findworks.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import com.findworks.platform.Ids;
import com.findworks.platform.security.CurrentInvestigatorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class FindingsController {
    private final JdbcClient db;
    private final CurrentInvestigatorService currentInvestigator;
    private final TransactionTemplate transactions;
    private final boolean testSupport;

    public FindingsController(JdbcClient db, CurrentInvestigatorService currentInvestigator, TransactionTemplate transactions,
                              @Value("${findworks.test-support:false}") boolean testSupport) {
        this.db = db;
        this.currentInvestigator = currentInvestigator;
        this.transactions = transactions;
        this.testSupport = testSupport;
    }

    @GetMapping("/app/findings/{sessionId}")
    String findings(@PathVariable UUID sessionId, Authentication authentication, Model model) {
        model.addAttribute("investigator", currentInvestigator.require(authentication));
        var session = ownedSession(sessionId, authentication);
        model.addAttribute("findingsSession", session);
        model.addAttribute("package", db.sql("""
                select p.id,p.current_version,p.accepted_version,v.state,v.notes
                from findings_packages p join package_versions v on v.package_id=p.id and v.version=p.current_version
                where p.session_id=:session
                """).param("session", sessionId).query((rs, row) -> new PackageView(rs.getObject(1, UUID.class), rs.getInt(2),
                (Integer) rs.getObject(3), rs.getString(4), rs.getString(5))).optional().orElse(null));
        var groups = new LinkedHashMap<UUID, ResultGroup>();
        db.sql("""
                select r.id,i.position,i.gap,i.required,r.coverage
                from findings_packages p
                join package_results pr on pr.package_id=p.id and pr.package_version=p.current_version
                join investigation_results r on r.id=pr.result_id
                join investigation_items i on i.id=r.item_id
                where p.session_id=:session order by i.position,r.id
                """).param("session", sessionId).query((rs, row) -> new ResultGroup(
                        rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getBoolean(4), rs.getString(5),
                        new ArrayList<>(), new ArrayList<>())).list().forEach(group -> groups.put(group.id(), group));
        var knowledgeRows = db.sql("""
                select k.result_id,k.id,v.claim,v.category,v.review_state,e.id,e.exact_text,ke.quotation,
                       e.created_at,p.name,ke.start_offset,ke.end_offset
                from knowledge_items k join findings_packages fp on fp.id=k.package_id and fp.current_version=k.package_version
                join knowledge_versions v on v.knowledge_id=k.id and v.version=k.current_version
                left join knowledge_evidence ke on ke.knowledge_id=k.id and ke.knowledge_version=v.version
                left join evidence e on e.id=ke.evidence_id
                left join participants p on p.id=e.participant_id
                where fp.session_id=:session order by k.id,e.created_at,e.id
                """).param("session", sessionId).query((rs, row) -> new KnowledgeRow(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, UUID.class), rs.getString(7), rs.getString(8), rs.getObject(9),
                        rs.getString(10), (Integer) rs.getObject(11), (Integer) rs.getObject(12))).list();
        var knowledge = new LinkedHashMap<UUID, KnowledgeView>();
        for (var row : knowledgeRows) {
            var item = knowledge.computeIfAbsent(row.id(), id -> new KnowledgeView(
                    id, row.resultId(), row.claim(), row.category(), row.reviewState(), new ArrayList<>()));
            if (row.evidenceId() != null) item.sources().add(new KnowledgeSource(row.evidenceId(), row.answer(), row.quote(),
                    row.evidenceAt(), row.participant(), row.startOffset(), row.endOffset()));
        }
        knowledge.values().forEach(item -> {
            var group = groups.get(item.resultId());
            if (group != null) group.knowledge().add(item);
        });
        var unresolved = db.sql("""
                select u.result_id,u.id,u.kind,u.summary,u.reason,u.owner,u.acknowledged,e.id,e.exact_text,e.created_at,p.name,
                       (select count(*) from conflict_members cm where cm.conflict_id=u.id)
                from unresolved u join investigation_results r on r.id=u.result_id
                join findings_packages fp on fp.session_id=r.session_id
                join package_results pr on pr.package_id=fp.id and pr.package_version=fp.current_version and pr.result_id=r.id
                left join lateral(select evidence_id from unresolved_evidence where unresolved_id=u.id order by evidence_id limit 1) ue on true
                left join evidence e on e.id=coalesce(ue.evidence_id,u.evidence_id)
                left join participants p on p.id=e.participant_id
                where r.session_id=:session order by u.id
                """).param("session", sessionId).query((rs, row) -> new UnresolvedView(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getBoolean(7), rs.getObject(8, UUID.class), rs.getString(9), rs.getObject(10), rs.getString(11), rs.getInt(12))).list();
        unresolved.forEach(item -> {
            var group = groups.get(item.resultId());
            if (group != null) group.unresolved().add(item);
        });
        model.addAttribute("groups", groups.values());
        var required = groups.values().stream().filter(ResultGroup::required).count();
        var terminal = groups.values().stream().filter(ResultGroup::required)
                .filter(group -> !List.of("UNADDRESSED", "EXPLORING", "PARTIAL").contains(group.coverage())).count();
        model.addAttribute("coverage", terminal + " of " + required + " required areas have an explicit outcome");
        return "findings-review";
    }

    @PostMapping("/app/findings/{sessionId}/retry")
    String retryExtraction(@PathVariable UUID sessionId, Authentication authentication) {
        ownedSession(sessionId, authentication);
        if (db.sql("update interview_sessions set extraction_state='PENDING' where id=:session and extraction_state='FAILED'")
                .param("session", sessionId).update() != 1) throw new ResponseStatusException(HttpStatus.CONFLICT);
        return "redirect:/app/findings/" + sessionId;
    }

    @PostMapping("/test/extraction/run-once")
    @ResponseBody
    ResponseEntity<?> extractOnce() {
        if (!testSupport) return ResponseEntity.notFound().build();
        var outcome = processExtractionNext();
        return outcome == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(java.util.Map.of("event", outcome));
    }

    String processExtractionNext() {
        return transactions.execute(status -> {
            var session = db.sql("""
                    select s.id,s.organization_id from interview_sessions s
                    where s.state='COMPLETED' and s.extraction_state in ('PENDING','FAILED')
                    order by s.completed_at limit 1 for update skip locked
                    """).query((rs, row) -> new Extractable(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional().orElse(null);
            if (session == null) return null;
            var fail = db.sql("update fault_controls set remaining=remaining-1 where kind='extraction' and remaining>0").update();
            if (fail == 1) {
                db.sql("update interview_sessions set extraction_state='FAILED' where id=:id").param("id", session.id()).update();
                return "findings_failed";
            }
            var packageId = db.sql("select id from findings_packages where session_id=:session")
                    .param("session", session.id()).query(UUID.class).optional().orElseGet(Ids::id);
            var existing = db.sql("select count(*) from findings_packages where id=:id").param("id", packageId).query(Long.class).single() > 0;
            var version = existing ? db.sql("select current_version+1 from findings_packages where id=:id").param("id", packageId).query(Integer.class).single() : 1;
            if (!existing) {
                db.sql("insert into findings_packages(id,organization_id,session_id,current_version) values(:id,:organization,:session,:version)")
                        .param("id", packageId).param("organization", session.organizationId()).param("session", session.id()).param("version", version).update();
            } else {
                db.sql("update findings_packages set current_version=:version where id=:id").param("version", version).param("id", packageId).update();
            }
            db.sql("insert into package_versions(package_id,organization_id,version,state) values(:package,:organization,:version,'READY')")
                    .param("package", packageId).param("organization", session.organizationId()).param("version", version).update();
            db.sql("insert into package_results select :package,:version,id from investigation_results where session_id=:session")
                    .param("package", packageId).param("version", version).param("session", session.id()).update();
            var proposals = db.sql("""
                    select o.id,o.result_id,o.category,o.summary from result_outcomes o
                    join investigation_results r on r.id=o.result_id
                    where r.session_id=:session and o.coverage in ('SUPPORTED','ASSUMPTION')
                    order by r.id,o.created_at,o.id
                    """).param("session", session.id()).query((rs, row) -> new KnowledgeProposal(
                    rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4))).list();
            for (var item : proposals) {
                var knowledgeId = Ids.id();
                db.sql("insert into knowledge_items values(:id,:organization,:result,:package,:version,1)")
                        .param("id", knowledgeId).param("organization", session.organizationId()).param("result", item.resultId())
                        .param("package", packageId).param("version", version).update();
                db.sql("insert into knowledge_versions values(:id,1,:category,:claim,'UNREVIEWED',false,null)")
                        .param("id", knowledgeId).param("category", item.category()).param("claim", item.summary()).update();
                var evidence = db.sql("""
                        select e.id,e.exact_text from result_outcome_evidence oe join evidence e on e.id=oe.evidence_id
                        where oe.outcome_id=:outcome order by e.created_at,e.id
                        """).param("outcome", item.id()).query((rs, row) -> new SourceEvidence(
                        rs.getObject(1, UUID.class), rs.getString(2))).list();
                for (var source : evidence) {
                    var quote = source.text().substring(0, Math.min(500, source.text().length()));
                    db.sql("insert into knowledge_evidence values(:id,1,:evidence,:quote,0,:end)")
                            .param("id", knowledgeId).param("evidence", source.id()).param("quote", quote).param("end", quote.length()).update();
                }
            }
            db.sql("""
                    insert into conflict_members(conflict_id,knowledge_id,knowledge_version)
                    select u.id,k.id,k.current_version
                    from unresolved u
                    join investigation_results r on r.id=u.result_id
                    join knowledge_items k on k.result_id=r.id and k.package_id=:package and k.package_version=:version
                    where r.session_id=:session and u.kind='CONFLICT'
                    on conflict do nothing
                    """).param("package", packageId).param("version", version).param("session", session.id()).update();
            db.sql("update interview_sessions set extraction_state='READY' where id=:id").param("id", session.id()).update();
            return "findings_ready";
        });
    }

    @PostMapping("/app/findings/{sessionId}/knowledge/{knowledgeId}/decision")
    String decide(@PathVariable UUID sessionId, @PathVariable UUID knowledgeId, @RequestParam String decision,
                  Authentication authentication) {
        ownedSession(sessionId, authentication);
        if (!List.of("ACCEPTED", "REJECTED").contains(decision)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var changed = db.sql("""
                update knowledge_versions set review_state=:decision,confirmed=:confirmed
                where knowledge_id=:knowledge and version=(select current_version from knowledge_items where id=:knowledge)
                and exists(select 1 from knowledge_items k join findings_packages p on p.id=k.package_id where k.id=:knowledge and p.session_id=:session)
                """).param("decision", decision).param("confirmed", decision.equals("ACCEPTED"))
                .param("knowledge", knowledgeId).param("session", sessionId).update();
        if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return "redirect:/app/findings/" + sessionId;
    }

    @PostMapping("/app/findings/{sessionId}/knowledge/{knowledgeId}/correct")
    String correct(@PathVariable UUID sessionId, @PathVariable UUID knowledgeId, @RequestParam String claim,
                   Authentication authentication) {
        var session = ownedSession(sessionId, authentication);
        var investigator = currentInvestigator.require(authentication);
        if (claim.isBlank() || claim.length() > 2_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        transactions.executeWithoutResult(status -> {
            var current = db.sql("select k.current_version,v.category from knowledge_items k join knowledge_versions v on v.knowledge_id=k.id and v.version=k.current_version where k.id=:id for update of k,v")
                    .param("id", knowledgeId).query((rs, row) -> new CurrentKnowledge(rs.getInt(1), rs.getString(2))).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            var evidenceId = Ids.id();
            db.sql("insert into evidence(id,organization_id,session_id,source_type,exact_text,author_membership_id) values(:id,:organization,:session,'INVESTIGATOR_CORRECTION',:text,:author)")
                    .param("id", evidenceId).param("organization", session.organizationId()).param("session", sessionId)
                    .param("text", claim).param("author", investigator.membershipId()).update();
            db.sql("update knowledge_versions set review_state='CORRECTED' where knowledge_id=:id and version=:version")
                    .param("id", knowledgeId).param("version", current.version()).update();
            db.sql("insert into knowledge_versions values(:id,:version,:category,:claim,'ACCEPTED',true,:corrects)")
                    .param("id", knowledgeId).param("version", current.version() + 1).param("category", current.category())
                    .param("claim", claim).param("corrects", current.version()).update();
            db.sql("insert into knowledge_evidence values(:id,:version,:evidence,:quote,0,:end)")
                    .param("id", knowledgeId).param("version", current.version() + 1).param("evidence", evidenceId)
                    .param("quote", claim).param("end", claim.length()).update();
            db.sql("update knowledge_items set current_version=:version where id=:id")
                    .param("version", current.version() + 1).param("id", knowledgeId).update();
        });
        return "redirect:/app/findings/" + sessionId;
    }

    @PostMapping("/app/findings/{sessionId}/unresolved/{unresolvedId}/acknowledge")
    String acknowledge(@PathVariable UUID sessionId, @PathVariable UUID unresolvedId, Authentication authentication) {
        ownedSession(sessionId, authentication);
        var changed = db.sql("update unresolved set acknowledged=true where id=:id and result_id in(select id from investigation_results where session_id=:session)")
                .param("id", unresolvedId).param("session", sessionId).update();
        if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return "redirect:/app/findings/" + sessionId;
    }

    @PostMapping("/app/findings/{sessionId}/accept")
    String accept(@PathVariable UUID sessionId, @RequestParam int version,
                  @RequestParam(defaultValue = "") String notes, Authentication authentication) {
        var session = ownedSession(sessionId, authentication);
        if (notes.length() > 2_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        transactions.executeWithoutResult(status -> {
            var packageId = readyPackage(sessionId, version);
            var pending = db.sql("""
                    select count(*) from investigation_results r join investigation_items i on i.id=r.item_id
                    where r.session_id=:session and i.required and (
                      r.coverage in ('UNADDRESSED','EXPLORING','PARTIAL')
                      or exists(select 1 from knowledge_items k join knowledge_versions v on v.knowledge_id=k.id and v.version=k.current_version where k.result_id=r.id and v.review_state='UNREVIEWED')
                      or exists(select 1 from unresolved u where u.result_id=r.id and not u.acknowledged)
                      or exists(select 1 from unresolved u where u.result_id=r.id and u.kind='CONFLICT'
                                and (select count(*) from conflict_members cm where cm.conflict_id=u.id)<2)
                      or (not exists(select 1 from knowledge_items k where k.result_id=r.id)
                          and not exists(select 1 from unresolved u where u.result_id=r.id)))
                    """).param("session", sessionId).query(Long.class).single();
            if (pending > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Review every required outcome first");
            db.sql("update package_versions set state='ACCEPTED',notes=nullif(:notes,'') where package_id=:package and version=:version")
                    .param("notes", notes.trim()).param("package", packageId).param("version", version).update();
            db.sql("update findings_packages set accepted_version=:version where id=:package")
                    .param("version", version).param("package", packageId).update();
            db.sql("update publications set invalidated_at=now() where discovery_id=:discovery and invalidated_at is null")
                    .param("discovery", session.discoveryId()).update();
            db.sql("insert into publications values(:id,:organization,:discovery,:package,:version,now(),null)")
                    .param("id", Ids.id()).param("organization", session.organizationId()).param("discovery", session.discoveryId())
                    .param("package", packageId).param("version", version).update();
            db.sql("update discoveries d set retention_due_at=now()+(o.retention_days||' days')::interval,last_activity_at=now() from organizations o where d.id=:discovery and o.id=d.organization_id")
                    .param("discovery", session.discoveryId()).update();
        });
        return "redirect:/app/findings/" + sessionId;
    }

    @PostMapping("/app/findings/{sessionId}/reject")
    String reject(@PathVariable UUID sessionId, @RequestParam int version, @RequestParam String notes,
                  Authentication authentication) {
        var session = ownedSession(sessionId, authentication);
        notes = notes.trim();
        if (notes.isEmpty() || notes.length() > 2_000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var followUp = notes;
        transactions.executeWithoutResult(status -> {
            var packageId = readyPackage(sessionId, version);
            db.sql("update package_versions set state='REJECTED',notes=:notes where package_id=:package and version=:version")
                    .param("notes", followUp).param("package", packageId).param("version", version).update();
            db.sql("update discoveries d set retention_due_at=now()+(o.retention_days||' days')::interval,last_activity_at=now() from organizations o where d.id=:discovery and o.id=d.organization_id")
                    .param("discovery", session.discoveryId()).update();
        });
        return "redirect:/app/findings/" + sessionId;
    }

    private UUID readyPackage(UUID sessionId, int version) {
        return db.sql("""
                select p.id from findings_packages p
                join package_versions v on v.package_id=p.id and v.version=:version
                where p.session_id=:session and p.current_version=:version and v.state='READY'
                for update of p,v
                """).param("session", sessionId).param("version", version).query(UUID.class).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Decide the exact current package"));
    }

    private OwnedSession ownedSession(UUID sessionId, Authentication authentication) {
        var investigator = currentInvestigator.require(authentication);
        return db.sql("""
                select s.id,s.organization_id,s.discovery_id,s.extraction_state
                from interview_sessions s join discoveries d on d.id=s.discovery_id and d.organization_id=s.organization_id
                where s.id=:session and s.deleted_at is null and d.owner_membership_id=:member and d.organization_id=:organization and d.state='ACTIVE'
                """).param("session", sessionId).param("member", investigator.membershipId()).param("organization", investigator.organizationId())
                .query((rs, row) -> new OwnedSession(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4))).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    record OwnedSession(UUID id, UUID organizationId, UUID discoveryId, String extractionState) {}
    record PackageView(UUID id, int version, Integer acceptedVersion, String state, String notes) {}
    record ResultGroup(UUID id, int position, String gap, boolean required, String coverage,
                       List<KnowledgeView> knowledge, List<UnresolvedView> unresolved) {}
    record KnowledgeView(UUID id, UUID resultId, String claim, String category, String reviewState, List<KnowledgeSource> sources) {}
    record KnowledgeSource(UUID evidenceId, String answer, String quote, Object evidenceAt, String participant,
                           Integer startOffset, Integer endOffset) {}
    record UnresolvedView(UUID resultId, UUID id, String kind, String summary, String reason, String owner, boolean acknowledged,
                          UUID evidenceId, String answer, Object evidenceAt, String participant, int conflictMemberCount) {}
    private record Extractable(UUID id, UUID organizationId) {}
    private record KnowledgeProposal(UUID id, UUID resultId, String category, String summary) {}
    private record SourceEvidence(UUID id, String text) {}
    private record KnowledgeRow(UUID resultId, UUID id, String claim, String category, String reviewState, UUID evidenceId, String answer,
                                String quote, Object evidenceAt, String participant, Integer startOffset, Integer endOffset) {}
    private record CurrentKnowledge(int version, String category) {}
}
