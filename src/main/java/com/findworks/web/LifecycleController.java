package com.findworks.web;

import java.util.Map;
import java.util.UUID;

import com.findworks.platform.Ids;
import com.findworks.platform.security.CurrentInvestigatorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class LifecycleController {
    private final JdbcClient db;
    private final CurrentInvestigatorService current;
    private final TransactionTemplate transactions;
    private final EmailDeliveryService email;
    private final String operatorToken;
    private final String baseUrl;

    LifecycleController(JdbcClient db, CurrentInvestigatorService current, TransactionTemplate transactions,
                        EmailDeliveryService email, @Value("${findworks.operator-token}") String operatorToken,
                        @Value("${findworks.base-url}") String baseUrl) {
        this.db = db;
        this.current = current;
        this.transactions = transactions;
        this.email = email;
        this.operatorToken = operatorToken;
        this.baseUrl = baseUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    void reapplyDeletionLedger() {
        db.sql("update discoveries set state='DELETING',deleted_at=coalesce(deleted_at,now()) where id in(select resource_id from deletion_ledger where kind='DISCOVERY' and purged_at is null)").update();
        db.sql("update interview_sessions set deleted_at=coalesce(deleted_at,now()) where id in(select resource_id from deletion_ledger where kind='SESSION' and purged_at is null)").update();
    }

    @GetMapping("/app/discoveries/{discoveryId}")
    String discovery(@PathVariable UUID discoveryId, Authentication authentication, Model model) {
        var investigator = current.require(authentication);
        model.addAttribute("investigator", investigator);
        var view = db.sql("""
                select d.id,d.title,d.objective,d.retention_due_at,o.retention_days,
                  (d.retention_due_at is not null and d.retention_due_at<=now()+interval '14 days') warning
                from discoveries d join organizations o on o.id=d.organization_id
                where d.id=:id and d.organization_id=:organization and d.owner_membership_id=:member and d.state='ACTIVE'
                """).param("id", discoveryId).param("organization", investigator.organizationId()).param("member", investigator.membershipId())
                .query((rs, row) -> new DiscoveryView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4), rs.getInt(5), rs.getBoolean(6))).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("discovery", view);
        model.addAttribute("missions", db.sql("select id,label,current_version,approved_version from missions where discovery_id=:id order by created_at")
                .param("id", discoveryId).query((rs, row) -> Map.of("id", rs.getObject(1), "label", rs.getString(2),
                        "version", rs.getInt(3), "approved", rs.getObject(4) != null)).list());
        return "discovery-detail";
    }

    @PostMapping("/app/discoveries/{discoveryId}/delete")
    String deleteDiscovery(@PathVariable UUID discoveryId, Authentication authentication) {
        var investigator = current.require(authentication);
        transactions.executeWithoutResult(status -> {
            var changed = db.sql("update discoveries set state='DELETING',deleted_at=now() where id=:id and organization_id=:organization and owner_membership_id=:member and state='ACTIVE'")
                    .param("id", discoveryId).param("organization", investigator.organizationId()).param("member", investigator.membershipId()).update();
            if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            db.sql("update access_grants set revoked_at=now() where session_id in(select id from interview_sessions where discovery_id=:id) and revoked_at is null").param("id", discoveryId).update();
            db.sql("update invitations set state='REVOKED',revoked_at=now() where session_id in(select id from interview_sessions where discovery_id=:id) and state<>'REVOKED'").param("id", discoveryId).update();
            db.sql("update publications set invalidated_at=now() where discovery_id=:id and invalidated_at is null").param("id", discoveryId).update();
            db.sql("insert into deletion_ledger(id,organization_id,kind,resource_id) values(:ledger,:organization,'DISCOVERY',:resource)")
                    .param("ledger", Ids.id()).param("organization", investigator.organizationId()).param("resource", discoveryId).update();
            audit(investigator.organizationId(), investigator.membershipId(), "DISCOVERY_DELETION_REQUESTED", "DISCOVERY", discoveryId, "SUCCEEDED");
        });
        return "redirect:/app";
    }

    @PostMapping("/app/interviews/{sessionId}/delete")
    String deleteSession(@PathVariable UUID sessionId, Authentication authentication) {
        var investigator = current.require(authentication);
        transactions.executeWithoutResult(status -> {
            var row = db.sql("""
                    select s.organization_id,s.discovery_id from interview_sessions s join discoveries d on d.id=s.discovery_id
                    where s.id=:session and d.owner_membership_id=:member and d.organization_id=:organization and d.state='ACTIVE'
                    """).param("session", sessionId).param("member", investigator.membershipId()).param("organization", investigator.organizationId())
                    .query((rs, index) -> new SessionOwner(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            db.sql("update interview_sessions set deleted_at=now() where id=:session").param("session", sessionId).update();
            db.sql("update access_grants set revoked_at=now() where session_id=:session and revoked_at is null").param("session", sessionId).update();
            db.sql("update invitations set state='REVOKED',revoked_at=now() where session_id=:session and state<>'REVOKED'").param("session", sessionId).update();
            db.sql("update publications set invalidated_at=now() where package_id in(select id from findings_packages where session_id=:session) and invalidated_at is null").param("session", sessionId).update();
            db.sql("insert into deletion_ledger(id,organization_id,kind,resource_id) values(:ledger,:organization,'SESSION',:resource)")
                    .param("ledger", Ids.id()).param("organization", row.organizationId()).param("resource", sessionId).update();
            audit(row.organizationId(), investigator.membershipId(), "SESSION_DELETION_REQUESTED", "INTERVIEW_SESSION", sessionId, "SUCCEEDED");
        });
        return "redirect:/app";
    }

    @PostMapping("/operator/retention/run-once")
    @ResponseBody
    ResponseEntity<?> retention(@RequestHeader(value = "X-Operator-Token", required = false) String token) {
        operator(token);
        return ResponseEntity.ok(Map.of("warnings_created", processRetention()));
    }

    int processRetention() {
        return transactions.execute(status -> {
            var warnings = db.sql("""
                    select d.id,d.organization_id,u.email from discoveries d
                    join memberships m on m.id=d.owner_membership_id and m.organization_id=d.organization_id
                    join users u on u.id=m.user_id
                    where d.state='ACTIVE' and d.retention_due_at between now() and now()+interval '14 days'
                      and not exists(select 1 from retention_warnings w where w.discovery_id=d.id)
                    order by d.id for update of d skip locked
                    """).query((rs, row) -> new RetentionWarning(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3))).list();
            for (var warning : warnings) {
                db.sql("insert into retention_warnings(discovery_id) values(:id)").param("id", warning.discoveryId()).update();
                email.queueRetentionWarning(warning.discoveryId(), warning.organizationId(), warning.recipient(),
                        baseUrl + "/app/discoveries/" + warning.discoveryId());
            }
            db.sql("""
                    insert into deletion_ledger(id,organization_id,kind,resource_id)
                    select gen_random_uuid(),d.organization_id,'DISCOVERY',d.id from discoveries d join organizations o on o.id=d.organization_id
                    where d.state='ACTIVE' and ((d.retention_due_at is not null and d.retention_due_at<=now())
                      or (d.retention_due_at is null and d.last_activity_at<=now()-(o.never_reviewed_retention_days||' days')::interval))
                    and not exists(select 1 from deletion_ledger l where l.kind='DISCOVERY' and l.resource_id=d.id and l.purged_at is null)
                    """).update();
            db.sql("update discoveries set state='DELETING',deleted_at=now() where id in(select resource_id from deletion_ledger where kind='DISCOVERY' and purged_at is null) and state='ACTIVE'").update();
            db.sql("delete from audit_events where expires_at<=now()").update();
            return warnings.size();
        });
    }

    @PostMapping("/operator/retention/extend")
    @ResponseBody
    ResponseEntity<?> extend(@RequestHeader(value = "X-Operator-Token", required = false) String token,
                             @RequestParam UUID discoveryId, @RequestParam int days) {
        operator(token);
        if (days < 1 || days > 180) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var organization = db.sql("update discoveries set retention_due_at=coalesce(retention_due_at,now())+(:days||' days')::interval where id=:id and state='ACTIVE' returning organization_id")
                .param("days", days).param("id", discoveryId).query(UUID.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        audit(organization, null, "RETENTION_EXTENDED", "DISCOVERY", discoveryId, "SUCCEEDED");
        return ResponseEntity.ok(Map.of("extended_days", days));
    }

    @PostMapping("/operator/purge/run-once")
    @ResponseBody
    ResponseEntity<?> purge(@RequestHeader(value = "X-Operator-Token", required = false) String token) {
        operator(token);
        var purged = processPurge();
        return purged == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(Map.of("purged", purged));
    }

    UUID processPurge() {
        return transactions.execute(status -> {
            var item = db.sql("select id,organization_id,kind,resource_id from deletion_ledger where purged_at is null and purge_due_at<=now() order by requested_at limit 1 for update skip locked")
                    .query((rs, row) -> new PurgeItem(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class))).optional().orElse(null);
            if (item == null) return null;
            if (item.kind().equals("DISCOVERY")) purgeDiscovery(item.resourceId()); else purgeSession(item.resourceId());
            db.sql("update deletion_ledger set purged_at=now() where id=:id").param("id", item.id()).update();
            audit(item.organizationId(), null, item.kind() + "_PURGED", item.kind(), item.resourceId(), "SUCCEEDED");
            return item.resourceId();
        });
    }

    @PostMapping("/operator/invitations/{invitationId}/revoke")
    @ResponseBody
    ResponseEntity<?> revokeInvitation(@RequestHeader(value = "X-Operator-Token", required = false) String token,
                                       @PathVariable UUID invitationId) {
        operator(token);
        var row = db.sql("select organization_id,session_id from invitations where id=:id")
                .param("id", invitationId).query((rs, index) -> new InvitationOwner(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)))
                .optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        transactions.executeWithoutResult(status -> {
            db.sql("update invitations set state='REVOKED',revoked_at=now() where id=:id and state<>'REVOKED'").param("id", invitationId).update();
            db.sql("update access_grants set revoked_at=now() where session_id=:session and revoked_at is null").param("session", row.sessionId()).update();
            audit(row.organizationId(), null, "INVITATION_REVOKED", "INVITATION", invitationId, "SUCCEEDED");
        });
        return ResponseEntity.ok(Map.of("revoked", invitationId));
    }

    @PostMapping("/operator/sessions/{sessionId}/terminate")
    @ResponseBody
    ResponseEntity<?> terminateSession(@RequestHeader(value = "X-Operator-Token", required = false) String token,
                                       @PathVariable UUID sessionId) {
        operator(token);
        var organization = db.sql("select organization_id from interview_sessions where id=:id")
                .param("id", sessionId).query(UUID.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        transactions.executeWithoutResult(status -> {
            db.sql("update interview_sessions set state='TERMINATED',revision=revision+1 where id=:id and state<>'TERMINATED'").param("id", sessionId).update();
            db.sql("update access_grants set revoked_at=now() where session_id=:id and revoked_at is null").param("id", sessionId).update();
            db.sql("update invitations set state='REVOKED',revoked_at=now() where session_id=:id and state<>'REVOKED'").param("id", sessionId).update();
            audit(organization, null, "INTERVIEW_TERMINATED", "INTERVIEW_SESSION", sessionId, "SUCCEEDED");
        });
        return ResponseEntity.ok(Map.of("terminated", sessionId));
    }

    @PostMapping("/operator/discoveries/{discoveryId}/delete")
    @ResponseBody
    ResponseEntity<?> operatorDeleteDiscovery(@RequestHeader(value = "X-Operator-Token", required = false) String token,
                                              @PathVariable UUID discoveryId) {
        operator(token);
        transactions.executeWithoutResult(status -> {
            var organization = db.sql("update discoveries set state='DELETING',deleted_at=now() where id=:id and state='ACTIVE' returning organization_id")
                    .param("id", discoveryId).query(UUID.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            db.sql("update access_grants set revoked_at=now() where session_id in(select id from interview_sessions where discovery_id=:id) and revoked_at is null").param("id", discoveryId).update();
            db.sql("update invitations set state='REVOKED',revoked_at=now() where session_id in(select id from interview_sessions where discovery_id=:id) and state<>'REVOKED'").param("id", discoveryId).update();
            db.sql("update publications set invalidated_at=now() where discovery_id=:id and invalidated_at is null").param("id", discoveryId).update();
            db.sql("insert into deletion_ledger(id,organization_id,kind,resource_id) values(:ledger,:organization,'DISCOVERY',:resource)")
                    .param("ledger", Ids.id()).param("organization", organization).param("resource", discoveryId).update();
            audit(organization, null, "DISCOVERY_DELETION_REQUESTED", "DISCOVERY", discoveryId, "SUCCEEDED");
        });
        return ResponseEntity.ok(Map.of("deleting", discoveryId));
    }

    @PostMapping("/operator/sessions/{sessionId}/delete")
    @ResponseBody
    ResponseEntity<?> operatorDeleteSession(@RequestHeader(value = "X-Operator-Token", required = false) String token,
                                            @PathVariable UUID sessionId) {
        operator(token);
        transactions.executeWithoutResult(status -> {
            var organization = db.sql("update interview_sessions set deleted_at=now() where id=:id and deleted_at is null returning organization_id")
                    .param("id", sessionId).query(UUID.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            db.sql("update access_grants set revoked_at=now() where session_id=:id and revoked_at is null").param("id", sessionId).update();
            db.sql("update invitations set state='REVOKED',revoked_at=now() where session_id=:id and state<>'REVOKED'").param("id", sessionId).update();
            db.sql("update publications set invalidated_at=now() where package_id in(select id from findings_packages where session_id=:id) and invalidated_at is null").param("id", sessionId).update();
            db.sql("insert into deletion_ledger(id,organization_id,kind,resource_id) values(:ledger,:organization,'SESSION',:resource)")
                    .param("ledger", Ids.id()).param("organization", organization).param("resource", sessionId).update();
            audit(organization, null, "SESSION_DELETION_REQUESTED", "INTERVIEW_SESSION", sessionId, "SUCCEEDED");
        });
        return ResponseEntity.ok(Map.of("deleting", sessionId));
    }

    @PostMapping("/operator/runtime/{runId}/retry")
    @ResponseBody
    ResponseEntity<?> retryRuntime(@RequestHeader(value = "X-Operator-Token", required = false) String token,
                                   @PathVariable UUID runId) {
        operator(token);
        var retry = transactions.execute(status -> {
            var failed = db.sql("select organization_id,session_id,evidence_id,expected_revision from runtime_runs where id=:id and state='FAILED' for update")
                    .param("id", runId).query((rs, row) -> new FailedRun(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class), rs.getInt(4))).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
            var id = Ids.id();
            db.sql("insert into runtime_runs(id,organization_id,session_id,evidence_id,expected_revision,state) values(:id,:organization,:session,:evidence,:revision,'PENDING')")
                    .param("id", id).param("organization", failed.organizationId()).param("session", failed.sessionId())
                    .param("evidence", failed.evidenceId()).param("revision", failed.expectedRevision()).update();
            db.sql("update interview_sessions set state='ACTIVE' where id=:id and state='RUNTIME_FAILED'").param("id", failed.sessionId()).update();
            audit(failed.organizationId(), null, "RUNTIME_RETRY_REQUESTED", "RUNTIME_RUN", runId, "SUCCEEDED");
            return id;
        });
        return ResponseEntity.ok(Map.of("retry_run", retry));
    }

    @PostMapping("/operator/extractions/{sessionId}/retry")
    @ResponseBody
    ResponseEntity<?> retryExtraction(@RequestHeader(value = "X-Operator-Token", required = false) String token,
                                      @PathVariable UUID sessionId) {
        operator(token);
        var organization = db.sql("update interview_sessions set extraction_state='PENDING' where id=:id and extraction_state='FAILED' returning organization_id")
                .param("id", sessionId).query(UUID.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT));
        audit(organization, null, "EXTRACTION_RETRY_REQUESTED", "INTERVIEW_SESSION", sessionId, "SUCCEEDED");
        return ResponseEntity.ok(Map.of("retry_extraction", sessionId));
    }

    @GetMapping("/operator/status")
    @ResponseBody
    ResponseEntity<?> status(@RequestHeader(value = "X-Operator-Token", required = false) String token) {
        operator(token);
        audit(null, null, "OPERATOR_STATUS_READ", "OPERATIONS", new UUID(0, 0), "SUCCEEDED");
        return ResponseEntity.ok(Map.of("pending_email", db.sql("select count(*) from email_outbox where state in ('PENDING','RUNNING')").query(Long.class).single(),
                "failed_email", db.sql("select count(*) from email_outbox where state='FAILED'").query(Long.class).single(),
                "running_runtime", db.sql("select count(*) from runtime_runs where state='RUNNING'").query(Long.class).single(),
                "pending_deletions", db.sql("select count(*) from deletion_ledger where purged_at is null").query(Long.class).single(),
                "overdue_deletions", db.sql("select count(*) from deletion_ledger where purged_at is null and purge_due_at<=now()").query(Long.class).single(),
                "failed_runtime_runs", db.sql("select count(*) from runtime_runs where state='FAILED'").query(Long.class).single(),
                "failed_extractions", db.sql("select count(*) from interview_sessions where extraction_state='FAILED'").query(Long.class).single()));
    }

    private void purgeDiscovery(UUID discoveryId) {
        db.sql("delete from test_emails where recipient in(select email from participants where discovery_id=:id)").param("id", discoveryId).update();
        db.sql("delete from email_outbox where id=:id and kind='RETENTION_WARNING'").param("id", discoveryId).update();
        db.sql("delete from export_snapshots where discovery_id=:id").param("id", discoveryId).update();
        db.sql("delete from domain_outbox where resource_id in(select id from missions where discovery_id=:id)").param("id", discoveryId).update();
        db.sql("update submissions set deleted_at=coalesce(deleted_at,now()),discovery_id=null,mission_id=null where discovery_id=:id").param("id", discoveryId).update();
        db.sql("delete from publications where discovery_id=:id").param("id", discoveryId).update();
        db.sql("delete from knowledge_links where source_id in(select k.id from knowledge_items k join findings_packages p on p.id=k.package_id join interview_sessions s on s.id=p.session_id where s.discovery_id=:id) or target_id in(select k.id from knowledge_items k join findings_packages p on p.id=k.package_id join interview_sessions s on s.id=p.session_id where s.discovery_id=:id)").param("id", discoveryId).update();
        db.sql("delete from retention_warnings where discovery_id=:id").param("id", discoveryId).update();
        db.sql("delete from discoveries where id=:id").param("id", discoveryId).update();
    }

    private void purgeSession(UUID sessionId) {
        var participant = db.sql("select participant_id from interview_sessions where id=:id").param("id", sessionId).query(UUID.class).optional();
        db.sql("delete from export_snapshots where package_id in(select id from findings_packages where session_id=:id)").param("id", sessionId).update();
        db.sql("delete from publications where package_id in(select id from findings_packages where session_id=:id)").param("id", sessionId).update();
        db.sql("delete from knowledge_links where source_id in(select k.id from knowledge_items k join findings_packages p on p.id=k.package_id where p.session_id=:id) or target_id in(select k.id from knowledge_items k join findings_packages p on p.id=k.package_id where p.session_id=:id)").param("id", sessionId).update();
        db.sql("delete from interview_sessions where id=:id").param("id", sessionId).update();
        participant.ifPresent(id -> db.sql("delete from participants where id=:id and not exists(select 1 from interview_sessions where participant_id=:id)").param("id", id).update());
    }

    private void operator(String token) {
        if (operatorToken.equals("disabled") || token == null || !java.security.MessageDigest.isEqual(operatorToken.getBytes(), token.getBytes())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
    }

    private void audit(UUID organization, UUID actor, String action, String kind, UUID resource, String outcome) {
        db.sql("insert into audit_events(id,organization_id,actor_kind,actor_id,action,resource_kind,resource_id,outcome,correlation_id) values(:id,:organization,:actorKind,:actor,:action,:kind,:resource,:outcome,:correlation)")
                .param("id", Ids.id()).param("organization", organization).param("actorKind", actor == null ? "OPERATOR" : "INVESTIGATOR")
                .param("actor", actor).param("action", action).param("kind", kind).param("resource", resource).param("outcome", outcome).param("correlation", Ids.id()).update();
    }

    record DiscoveryView(UUID id, String title, String objective, Object retentionDueAt, int retentionDays, boolean warning) {}
    private record SessionOwner(UUID organizationId, UUID discoveryId) {}
    private record InvitationOwner(UUID organizationId, UUID sessionId) {}
    private record PurgeItem(UUID id, UUID organizationId, String kind, UUID resourceId) {}
    private record RetentionWarning(UUID discoveryId, UUID organizationId, String recipient) {}
    private record FailedRun(UUID organizationId, UUID sessionId, UUID evidenceId, int expectedRevision) {}
}
