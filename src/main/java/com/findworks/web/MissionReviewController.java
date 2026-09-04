package com.findworks.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.findworks.platform.security.CurrentInvestigatorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class MissionReviewController {

    private final JdbcClient db;
    private final CurrentInvestigatorService currentInvestigator;
    private final TransactionTemplate transactions;
    private final boolean emailDeliveryAvailable;

    public MissionReviewController(JdbcClient db, CurrentInvestigatorService currentInvestigator,
                                   TransactionTemplate transactions,
                                   @Value("${findworks.test-support:false}") boolean testSupport,
                                   @Value("${findworks.email-endpoint:disabled}") String emailEndpoint) {
        this.db = db;
        this.currentInvestigator = currentInvestigator;
        this.transactions = transactions;
        this.emailDeliveryAvailable = testSupport || !emailEndpoint.equals("disabled");
    }

    @GetMapping("/app/missions/{missionId}/review")
    String review(@PathVariable UUID missionId, Authentication authentication, Model model) {
        var investigator = currentInvestigator.require(authentication);
        var mission = findOwnedMission(missionId, investigator.organizationId(), investigator.membershipId());

        var entries = new LinkedHashMap<String, List<Entry>>();
        db.sql("""
                select kind, position, value, detail
                from mission_entries
                where mission_id = :missionId and version = :version
                order by kind, position
                """)
                .param("missionId", missionId)
                .param("version", mission.version())
                .query((rs, row) -> new Entry(rs.getString("kind"), rs.getInt("position"), rs.getString("value"), rs.getString("detail")))
                .list()
                .forEach(entry -> entries.computeIfAbsent(entry.kind(), ignored -> new ArrayList<>()).add(entry));

        var items = db.sql("""
                select position, gap, why, priority, required, context, sufficient
                from investigation_items
                where mission_id = :missionId and version = :version
                order by position
                """)
                .param("missionId", missionId)
                .param("version", mission.version())
                .query((rs, row) -> Map.of(
                        "position", rs.getInt("position"),
                        "gap", rs.getString("gap"),
                        "why", rs.getString("why"),
                        "priority", rs.getString("priority"),
                        "required", rs.getBoolean("required"),
                        "context", rs.getString("context"),
                        "sufficient", rs.getString("sufficient")))
                .list();
        var reviewedCount = db.sql("select count(*) from mission_review where mission_id=:id and version=:version")
                .param("id", missionId)
                .param("version", mission.version())
                .query(Integer.class)
                .single();
        var reviewed = db.sql("select checkpoint from mission_review where mission_id=:id and version=:version")
                .param("id", missionId).param("version", mission.version()).query(Integer.class).list();

        model.addAttribute("investigator", investigator);
        model.addAttribute("missionId", missionId);
        model.addAttribute("mission", mission);
        model.addAttribute("entries", entries);
        model.addAttribute("items", items);
        model.addAttribute("reviewedCount", reviewedCount);
        model.addAttribute("emailDeliveryAvailable", emailDeliveryAvailable);
        model.addAttribute("activeInvitation", db.sql("""
                select exists(
                    select 1 from invitations i join interview_sessions s on s.id=i.session_id
                    where s.mission_id=:mission and s.mission_version=:version
                      and i.state='DELIVERED' and i.redeemed_at is null and i.revoked_at is null and i.expires_at>now()
                )
                """).param("mission", missionId).param("version", mission.version()).query(Boolean.class).single());
        model.addAttribute("originBindingsComplete", originBindingsComplete(missionId, mission.version()));
        model.addAttribute("checkpoints", List.of(
                new Checkpoint(1, "Purpose", reviewed.contains(1)),
                new Checkpoint(2, "Intended interviewee", reviewed.contains(2)),
                new Checkpoint(3, "Investigation Items", reviewed.contains(3)),
                new Checkpoint(4, "Shared context and boundaries", reviewed.contains(4)),
                new Checkpoint(5, "Completion and data use", reviewed.contains(5))));
        model.addAttribute("editableFields", List.of(
                new EditableField("objective", "Objective", mission.objective()),
                new EditableField("desired_outcome", "Desired outcome", mission.desiredOutcome()),
                new EditableField("interviewee_role", "Interviewee role", mission.intervieweeRole()),
                new EditableField("interviewee_relevance", "Interviewee relevance", mission.intervieweeRelevance()),
                new EditableField("expected_minutes", "Expected minutes", Integer.toString(mission.expectedMinutes())),
                new EditableField("data_use", "Data use", mission.dataUse())));
        model.addAttribute("origins", db.sql("select pointer,authority from mission_origins where mission_id=:id and version=:version order by pointer")
                .param("id", missionId).param("version", mission.version())
                .query((rs, row) -> new Origin(rs.getString(1), rs.getString(2))).list());
        return "mission-review";
    }

    @PostMapping("/app/missions/{missionId}/review/checkpoints/{checkpoint}")
    String reviewCheckpoint(@PathVariable UUID missionId, @PathVariable int checkpoint,
                            @RequestParam int version, Authentication authentication) {
        if (checkpoint < 1 || checkpoint > 5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var investigator = currentInvestigator.require(authentication);
        var mission = findOwnedMission(missionId, investigator.organizationId(), investigator.membershipId());
        if (mission.version() != version || !mission.state().equals("DRAFT")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mission version changed");
        }
        db.sql("insert into mission_review(mission_id,version,checkpoint) values(:id,:version,:checkpoint) on conflict do nothing")
                .param("id", missionId)
                .param("version", version)
                .param("checkpoint", checkpoint)
                .update();
        return redirect(missionId);
    }

    @PostMapping("/app/missions/{missionId}/approve")
    String approve(@PathVariable UUID missionId, @RequestParam int version, Authentication authentication) {
        var investigator = currentInvestigator.require(authentication);
        transactions.executeWithoutResult(status -> {
            var mission = findOwnedMission(missionId, investigator.organizationId(), investigator.membershipId());
            var reviews = db.sql("select count(*) from mission_review where mission_id=:id and version=:version")
                    .param("id", missionId).param("version", version).query(Integer.class).single();
            if (mission.version() != version || !mission.state().equals("DRAFT") || reviews != 5
                    || !originBindingsComplete(missionId, version)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Complete all five checkpoints first");
            }
            db.sql("update mission_versions set state='APPROVED',approved_by=:member,approved_at=now() where mission_id=:id and version=:version")
                    .param("member", investigator.membershipId()).param("id", missionId).param("version", version).update();
            db.sql("update missions set approved_version=:version where id=:id")
                    .param("version", version).param("id", missionId).update();
        });
        return redirect(missionId);
    }

    @PostMapping("/app/missions/{missionId}/label")
    String rename(@PathVariable UUID missionId, @RequestParam String label, Authentication authentication) {
        var investigator = currentInvestigator.require(authentication);
        findOwnedMission(missionId, investigator.organizationId(), investigator.membershipId());
        var clean = label.trim();
        if (clean.isEmpty() || clean.length() > 120) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        db.sql("update missions set label=:label where id=:id and organization_id=:organization")
                .param("label", clean).param("id", missionId).param("organization", investigator.organizationId()).update();
        return redirect(missionId);
    }

    @PostMapping("/app/missions/{missionId}/edit")
    String edit(@PathVariable UUID missionId, @RequestParam int version, @RequestParam String field,
                @RequestParam String value, Authentication authentication) {
        var investigator = currentInvestigator.require(authentication);
        var columns = Map.of(
                "objective", "objective",
                "desired_outcome", "desired_outcome",
                "interviewee_role", "interviewee_role",
                "interviewee_relevance", "interviewee_relevance",
                "expected_minutes", "expected_minutes",
                "data_use", "data_use");
        var column = columns.get(field);
        var clean = value.trim();
        if (column == null || clean.isEmpty() || clean.length() > 4_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (field.equals("expected_minutes")) {
            try {
                var minutes = Integer.parseInt(clean);
                if (minutes < 5 || minutes > 120) throw new NumberFormatException();
            } catch (NumberFormatException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected minutes must be from 5 through 120");
            }
        }
        transactions.executeWithoutResult(status -> {
            var next = copyAsDraft(missionId, version, investigator.organizationId(), investigator.membershipId());
            db.sql("update mission_versions set " + column + "=cast(:value as " + (field.equals("expected_minutes") ? "integer" : "text") + ") where mission_id=:id and version=:version")
                    .param("value", clean).param("id", missionId).param("version", next).update();
            var pointer = Map.of(
                    "objective", "objective", "desired_outcome", "desired_outcome",
                    "interviewee_role", "intended_interviewee_role",
                    "interviewee_relevance", "intended_interviewee_relevance",
                    "expected_minutes", "expected_commitment_minutes", "data_use", "data_use_summary").get(field);
            markDirectOrigin(missionId, next, "/mission/" + pointer, investigator.membershipId());
        });
        return redirect(missionId);
    }

    @PostMapping("/app/missions/{missionId}/entries/{kind}/{position}/edit")
    String editEntry(@PathVariable UUID missionId, @PathVariable String kind, @PathVariable int position,
                     @RequestParam int version, @RequestParam String value,
                     @RequestParam(required = false) String detail, Authentication authentication) {
        var fields = Map.of("SHARED_CONTEXT", "shared_context", "BOUNDARY", "boundaries",
                "PROHIBITED_TOPIC", "prohibited_topics", "OPENING_QUESTION", "opening_questions",
                "COMPLETION_CRITERION", "completion_criteria", "TERM", "terminology");
        var field = fields.get(kind); var clean = value.trim(); var cleanDetail = detail == null ? null : detail.trim();
        if (field == null || position < 0 || clean.isEmpty() || clean.length() > 4_000
                || (kind.equals("TERM") && (cleanDetail == null || cleanDetail.isEmpty() || cleanDetail.length() > 4_000))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        var investigator = currentInvestigator.require(authentication);
        transactions.executeWithoutResult(status -> {
            if (db.sql("select count(*) from mission_entries where mission_id=:id and version=:version and kind=:kind and position=:position")
                    .param("id", missionId).param("version", version).param("kind", kind).param("position", position)
                    .query(Long.class).single() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            var next = copyAsDraft(missionId, version, investigator.organizationId(), investigator.membershipId());
            db.sql("update mission_entries set value=:value,detail=:detail where mission_id=:id and version=:version and kind=:kind and position=:position")
                    .param("value", clean).param("detail", kind.equals("TERM") ? cleanDetail : null).param("id", missionId)
                    .param("version", next).param("kind", kind).param("position", position).update();
            markDirectOrigin(missionId, next, "/mission/" + field + "/" + position, investigator.membershipId());
        });
        return redirect(missionId);
    }

    @PostMapping("/app/missions/{missionId}/items/{position}/edit")
    String editItem(@PathVariable UUID missionId, @PathVariable int position, @RequestParam int version,
                    @RequestParam String gap, @RequestParam String why, @RequestParam String priority,
                    @RequestParam(defaultValue = "false") boolean required, @RequestParam String context,
                    @RequestParam String sufficient, Authentication authentication) {
        var values = List.of(gap.trim(), why.trim(), context.trim(), sufficient.trim());
        if (position < 0 || values.stream().anyMatch(String::isEmpty) || values.stream().anyMatch(value -> value.length() > 4_000)
                || !List.of("HIGH", "MEDIUM", "LOW").contains(priority)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        var investigator = currentInvestigator.require(authentication);
        transactions.executeWithoutResult(status -> {
            if (db.sql("select count(*) from investigation_items where mission_id=:id and version=:version and position=:position")
                    .param("id", missionId).param("version", version).param("position", position).query(Long.class).single() != 1) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            }
            var next = copyAsDraft(missionId, version, investigator.organizationId(), investigator.membershipId());
            db.sql("""
                    update investigation_items set gap=:gap,why=:why,priority=:priority,required=:required,context=:context,sufficient=:sufficient
                    where mission_id=:id and version=:version and position=:position
                    """).param("gap", values.get(0)).param("why", values.get(1)).param("priority", priority)
                    .param("required", required).param("context", values.get(2)).param("sufficient", values.get(3))
                    .param("id", missionId).param("version", next).param("position", position).update();
            markDirectOrigin(missionId, next, "/mission/investigation_items/" + position, investigator.membershipId());
        });
        return redirect(missionId);
    }

    private Mission findOwnedMission(UUID missionId, UUID organizationId, UUID membershipId) {
        return db.sql("""
                select m.label, m.current_version, mv.state, mv.objective, mv.desired_outcome,
                       mv.interviewee_role, mv.interviewee_relevance, mv.expected_minutes, mv.data_use,
                       exists(select 1 from mission_versions old where old.mission_id=m.id and old.version<m.current_version and old.approved_at is not null) as approval_superseded
                from missions m
                join discoveries d on d.id = m.discovery_id and d.organization_id = m.organization_id
                join mission_versions mv on mv.mission_id = m.id and mv.version = m.current_version
                where m.id = :missionId
                  and m.organization_id = :organizationId
                  and d.owner_membership_id = :membershipId
                  and d.state = 'ACTIVE'
                """)
                .param("missionId", missionId)
                .param("organizationId", organizationId)
                .param("membershipId", membershipId)
                .query((rs, row) -> new Mission(
                        rs.getString("label"),
                        rs.getInt("current_version"),
                        rs.getString("state"),
                        rs.getString("objective"),
                        rs.getString("desired_outcome"),
                        rs.getString("interviewee_role"),
                        rs.getString("interviewee_relevance"),
                        rs.getInt("expected_minutes"),
                        rs.getString("data_use"),
                        rs.getBoolean("approval_superseded")))
                .optional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private boolean originBindingsComplete(UUID missionId, int version) {
        var required = new HashSet<>(List.of(
                "/mission/objective", "/mission/desired_outcome", "/mission/intended_interviewee_role",
                "/mission/intended_interviewee_relevance", "/mission/expected_commitment_minutes",
                "/mission/data_use_summary"));
        db.sql("select position from investigation_items where mission_id=:id and version=:version")
                .param("id", missionId).param("version", version).query(Integer.class).list()
                .forEach(position -> required.add("/mission/investigation_items/" + position));
        var fields = Map.of("SHARED_CONTEXT", "shared_context", "BOUNDARY", "boundaries",
                "PROHIBITED_TOPIC", "prohibited_topics", "OPENING_QUESTION", "opening_questions",
                "COMPLETION_CRITERION", "completion_criteria", "TERM", "terminology");
        db.sql("select kind,position from mission_entries where mission_id=:id and version=:version")
                .param("id", missionId).param("version", version)
                .query((rs, row) -> "/mission/" + fields.get(rs.getString(1)) + "/" + rs.getInt(2)).list()
                .forEach(required::add);
        var present = new HashSet<>(db.sql("select pointer from mission_origins where mission_id=:id and version=:version")
                .param("id", missionId).param("version", version).query(String.class).list());
        return present.containsAll(required);
    }

    private int copyAsDraft(UUID missionId, int from, UUID organizationId, UUID membershipId) {
        var mission = findOwnedMission(missionId, organizationId, membershipId);
        if (mission.version() != from) throw new ResponseStatusException(HttpStatus.CONFLICT, "Mission version changed");
        var to = from + 1;
        db.sql("""
                insert into mission_versions(mission_id,organization_id,version,state,objective,desired_outcome,
                    interviewee_role,interviewee_relevance,expected_minutes,data_use,created_by)
                select mission_id,organization_id,:to,'DRAFT',objective,desired_outcome,interviewee_role,
                    interviewee_relevance,expected_minutes,data_use,:member
                from mission_versions where mission_id=:id and version=:from
                """).param("to", to).param("member", membershipId).param("id", missionId).param("from", from).update();
        cloneVersion(missionId, from, to);
        db.sql("update mission_versions set state='SUPERSEDED' where mission_id=:id and version=:from")
                .param("id", missionId).param("from", from).update();
        if (db.sql("update missions set current_version=:to,approved_version=null where id=:id and current_version=:from")
                .param("to", to).param("id", missionId).param("from", from).update() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mission version changed");
        }
        revokeUnusedAccess(missionId);
        return to;
    }

    private void cloneVersion(UUID missionId, int from, int to) {
        db.sql("insert into mission_entries select mission_id,:to,kind,position,value,detail from mission_entries where mission_id=:id and version=:from")
                .param("to", to).param("id", missionId).param("from", from).update();
        db.sql("""
                insert into investigation_items
                select gen_random_uuid(),mission_id,:to,position,gap,why,priority,required,context,sufficient
                from investigation_items where mission_id=:id and version=:from
                """).param("to", to).param("id", missionId).param("from", from).update();
        db.sql("insert into mission_origins select mission_id,:to,pointer,authority,author,created_at from mission_origins where mission_id=:id and version=:from")
                .param("to", to).param("id", missionId).param("from", from).update();
        db.sql("""
                insert into project_references
                select gen_random_uuid(),mission_id,:to,kind,locator,line_start,line_end,revision
                from project_references where mission_id=:id and version=:from
                """).param("to", to).param("id", missionId).param("from", from).update();
    }

    private void markDirectOrigin(UUID missionId, int version, String pointer, UUID author) {
        db.sql("""
                insert into mission_origins(mission_id,version,pointer,authority,author,created_at)
                values(:id,:version,:pointer,'DIRECT_EDIT',:author,now())
                on conflict(mission_id,version,pointer) do update
                set authority='DIRECT_EDIT',author=excluded.author,created_at=excluded.created_at
                """).param("id", missionId).param("version", version).param("pointer", pointer).param("author", author).update();
    }

    private void revokeUnusedAccess(UUID missionId) {
        db.sql("""
                update access_grants set revoked_at=now()
                where session_id in (select id from interview_sessions where mission_id=:id and state='NOT_STARTED')
                """).param("id", missionId).update();
        db.sql("""
                update invitations set state='REVOKED',revoked_at=now()
                where session_id in (select id from interview_sessions where mission_id=:id and state='NOT_STARTED')
                  and state <> 'REVOKED'
                """).param("id", missionId).update();
    }

    private String redirect(UUID missionId) {
        return "redirect:/app/missions/" + missionId + "/review";
    }

    public record Mission(String label, int version, String state, String objective, String desiredOutcome,
                          String intervieweeRole, String intervieweeRelevance, int expectedMinutes,
                          String dataUse, boolean approvalSuperseded) {}

    public record Entry(String kind, int position, String value, String detail) {}
    public record Checkpoint(int number, String label, boolean reviewed) {}
    public record EditableField(String name, String label, String value) {}
    public record Origin(String pointer, String authority) {}
}
