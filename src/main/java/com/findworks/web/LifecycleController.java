package com.findworks.web;

import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class LifecycleController {
    private final JdbcClient db;
    private final CurrentInvestigatorService current;
    private final TransactionTemplate transactions;
    private final String operatorToken;
    private final MissionWorkQuery missionWork;
    private final DeletionService deletion;

    LifecycleController(JdbcClient db, CurrentInvestigatorService current, TransactionTemplate transactions,
                        @Value("${findworks.operator-token}") String operatorToken, MissionWorkQuery missionWork,
                        DeletionService deletion) {
        this.db = db;
        this.current = current;
        this.transactions = transactions;
        this.operatorToken = operatorToken;
        this.missionWork = missionWork;
        this.deletion = deletion;
    }

    @GetMapping("/app/discoveries/{discoveryId}")
    String discovery(@PathVariable UUID discoveryId, Authentication authentication, Model model) {
        var investigator = current.require(authentication);
        model.addAttribute("investigator", investigator);
        var view = db.sql("""
                select d.id,d.title,d.objective
                from discoveries d
                where d.id=:id and d.organization_id=:organization and d.owner_membership_id=:member and d.state='ACTIVE'
                """).param("id", discoveryId).param("organization", investigator.organizationId()).param("member", investigator.membershipId())
                .query((rs, row) -> new DiscoveryView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("discovery", view);
        model.addAttribute("missions", missionWork.listForDiscovery(investigator, discoveryId));
        return "discovery-detail";
    }

    @PostMapping("/app/discoveries/{discoveryId}/delete")
    String deleteDiscovery(@PathVariable UUID discoveryId, Authentication authentication) {
        current.require(authentication);
        throw deletion.unavailable();
    }

    @PostMapping("/app/interviews/{sessionId}/delete")
    String deleteSession(@PathVariable UUID sessionId, Authentication authentication) {
        current.require(authentication);
        throw deletion.unavailable();
    }

    @PostMapping("/operator/retention/run-once")
    @ResponseBody
    ResponseEntity<?> retention(@RequestHeader(value = "X-Operator-Token", required = false) String token) {
        operator(token);
        throw deletion.unavailable();
    }

    @PostMapping("/operator/retention/extend")
    @ResponseBody
    ResponseEntity<?> extend(@RequestHeader(value = "X-Operator-Token", required = false) String token,
                             @RequestParam UUID discoveryId, @RequestParam int days) {
        operator(token);
        throw deletion.unavailable();
    }

    @PostMapping("/operator/purge/run-once")
    @ResponseBody
    ResponseEntity<?> purge(@RequestHeader(value = "X-Operator-Token", required = false) String token) {
        operator(token);
        throw deletion.unavailable();
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
        throw deletion.unavailable();
    }

    @PostMapping("/operator/sessions/{sessionId}/delete")
    @ResponseBody
    ResponseEntity<?> operatorDeleteSession(@RequestHeader(value = "X-Operator-Token", required = false) String token,
                                            @PathVariable UUID sessionId) {
        operator(token);
        throw deletion.unavailable();
    }

    @PostMapping("/operator/runtime/{runId}/retry")
    @ResponseBody
    ResponseEntity<?> retryRuntime(@RequestHeader(value = "X-Operator-Token", required = false) String token,
                                   @PathVariable UUID runId) {
        operator(token);
        var retry = transactions.execute(status -> {
            var source = db.sql("select organization_id,session_id,evidence_id,expected_revision,state from runtime_runs where id=:id for update")
                    .param("id", runId).query((rs, row) -> new RetrySource(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class), rs.getInt(4), rs.getString(5))).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            var existing = db.sql("select id from runtime_runs where session_id=:session and state in ('PENDING','RUNNING') order by created_at desc limit 1")
                    .param("session", source.sessionId()).query(UUID.class).optional();
            if (!source.state().equals("FAILED") || existing.isPresent()) {
                if (existing.isPresent() && source.state().equals("FAILED")) {
                    db.sql("update runtime_runs set state='SUPERSEDED' where id=:id and state='FAILED'").param("id", runId).update();
                }
                return existing.orElse(runId);
            }
            var id = Ids.id();
            db.sql("update runtime_runs set state='SUPERSEDED' where id=:id and state='FAILED'").param("id", runId).update();
            db.sql("insert into runtime_runs(id,organization_id,session_id,evidence_id,expected_revision,state) values(:id,:organization,:session,:evidence,:revision,'PENDING')")
                    .param("id", id).param("organization", source.organizationId()).param("session", source.sessionId())
                    .param("evidence", source.evidenceId()).param("revision", source.expectedRevision()).update();
            db.sql("update interview_sessions set state='ACTIVE' where id=:id and state='RUNTIME_FAILED'").param("id", source.sessionId()).update();
            audit(source.organizationId(), null, "RUNTIME_RETRY_REQUESTED", "RUNTIME_RUN", runId, "SUCCEEDED");
            return id;
        });
        return ResponseEntity.ok(Map.of("retry_run", retry));
    }

    @PostMapping("/operator/extractions/{sessionId}/retry")
    @ResponseBody
    ResponseEntity<?> retryExtraction(@RequestHeader(value = "X-Operator-Token", required = false) String token,
                                      @PathVariable UUID sessionId) {
        operator(token);
        var organization = db.sql("select organization_id from interview_sessions where id=:id")
                .param("id", sessionId).query(UUID.class).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var changed = db.sql("update interview_sessions set extraction_state='PENDING' where id=:id and extraction_state='FAILED'")
                .param("id", sessionId).update();
        if (changed == 1) audit(organization, null, "EXTRACTION_RETRY_REQUESTED", "INTERVIEW_SESSION", sessionId, "SUCCEEDED");
        return ResponseEntity.ok(Map.of("retry_extraction", sessionId));
    }

    @GetMapping("/operator/status")
    @ResponseBody
    ResponseEntity<?> status(@RequestHeader(value = "X-Operator-Token", required = false) String token) {
        operator(token);
        audit(null, null, "OPERATOR_STATUS_READ", "OPERATIONS", new UUID(0, 0), "SUCCEEDED");
        return ResponseEntity.ok(Map.of("pending_email", db.sql("select count(*) from email_outbox where kind='INVITATION' and state in ('PENDING','RUNNING')").query(Long.class).single(),
                "failed_email", db.sql("select count(*) from email_outbox where kind='INVITATION' and state='FAILED'").query(Long.class).single(),
                "running_runtime", db.sql("select count(*) from runtime_runs where state='RUNNING'").query(Long.class).single(),
                "deferred_runtime_retries", db.sql("select count(*) from runtime_runs where state='PENDING' and available_at>now()").query(Long.class).single(),
                "runtime_circuit_open", db.sql("select coalesce(open_until>now(),false) from runtime_circuit_breakers where dependency='MODEL_PROVIDER'").query(Boolean.class).single(),
                "deletion_enabled", false,
                "failed_runtime_runs", db.sql("select count(*) from runtime_runs where state='FAILED'").query(Long.class).single(),
                "failed_extractions", db.sql("select count(*) from interview_sessions where extraction_state='FAILED'").query(Long.class).single()));
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

    record DiscoveryView(UUID id, String title, String objective) {}
    private record InvitationOwner(UUID organizationId, UUID sessionId) {}
    private record RetrySource(UUID organizationId, UUID sessionId, UUID evidenceId, int expectedRevision, String state) {}
}
