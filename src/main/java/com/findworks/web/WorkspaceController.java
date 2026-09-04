package com.findworks.web;

import java.util.List;
import java.util.UUID;

import com.findworks.platform.security.AuthenticatedInvestigator;
import com.findworks.platform.security.CurrentInvestigatorService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WorkspaceController {

    private final JdbcClient db;
    private final CurrentInvestigatorService currentInvestigator;

    public WorkspaceController(JdbcClient db, CurrentInvestigatorService currentInvestigator) {
        this.db = db;
        this.currentInvestigator = currentInvestigator;
    }

    @GetMapping("/app")
    String dashboard(Authentication authentication, Model model) {
        var investigator = currentInvestigator.require(authentication);
        var missions = missions(investigator);
        var discoveries = discoveries(investigator);
        var attention = missions.stream().filter(MissionRow::needsAttention).limit(8).toList();
        model.addAttribute("investigator", investigator);
        model.addAttribute("attention", attention);
        model.addAttribute("recentMissions", missions.stream().limit(8).toList());
        model.addAttribute("recentDiscoveries", discoveries.stream().limit(4).toList());
        model.addAttribute("discoveryCount", discoveries.size());
        model.addAttribute("missionCount", missions.size());
        model.addAttribute("attentionCount", missions.stream().filter(MissionRow::needsAttention).count());
        model.addAttribute("activeInterviewCount", missions.stream().filter(MissionRow::interviewActive).count());
        return "app";
    }

    @GetMapping("/app/discoveries")
    String discoveryIndex(Authentication authentication, Model model) {
        var investigator = currentInvestigator.require(authentication);
        model.addAttribute("investigator", investigator);
        model.addAttribute("discoveries", discoveries(investigator));
        return "discoveries";
    }

    @GetMapping("/app/missions")
    String missionIndex(Authentication authentication, Model model) {
        var investigator = currentInvestigator.require(authentication);
        model.addAttribute("investigator", investigator);
        model.addAttribute("missions", missions(investigator));
        return "missions";
    }

    @GetMapping("/app/findings")
    String findingsIndex(Authentication authentication, Model model) {
        var investigator = currentInvestigator.require(authentication);
        model.addAttribute("investigator", investigator);
        model.addAttribute("findings", findings(investigator));
        return "findings-index";
    }

    private List<DiscoveryRow> discoveries(AuthenticatedInvestigator investigator) {
        return db.sql("""
                select d.id,d.title,d.objective,count(distinct m.id),
                       count(distinct case when s.state in ('ACTIVE','PAUSED','AWAITING_CONFIRMATION','RUNTIME_FAILED') then s.id end),
                       count(distinct case when s.extraction_state in ('READY','FAILED') and coalesce(pv.state,'READY')='READY' then s.id end)
                from discoveries d
                left join missions m on m.discovery_id=d.id
                left join interview_sessions s on s.mission_id=m.id and s.deleted_at is null
                left join findings_packages fp on fp.session_id=s.id
                left join package_versions pv on pv.package_id=fp.id and pv.version=fp.current_version
                where d.organization_id=:organization and d.owner_membership_id=:member and d.state='ACTIVE'
                group by d.id order by d.last_activity_at desc,d.id
                """).param("organization", investigator.organizationId()).param("member", investigator.membershipId())
                .query((rs, row) -> new DiscoveryRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getLong(6))).list();
    }

    private List<MissionRow> missions(AuthenticatedInvestigator investigator) {
        return db.sql("""
                select m.id,d.id,d.title,m.label,m.current_version,mv.state,
                       s.id,s.state,s.extraction_state,pv.state,
                       exists(select 1 from invitations i where i.session_id=s.id and i.state='DELIVERED'
                         and i.redeemed_at is null and i.revoked_at is null and i.expires_at>now())
                from missions m
                join discoveries d on d.id=m.discovery_id and d.organization_id=m.organization_id
                join mission_versions mv on mv.mission_id=m.id and mv.version=m.current_version
                left join lateral (
                    select x.id,x.state,x.extraction_state from interview_sessions x
                    where x.mission_id=m.id and x.deleted_at is null order by x.started_at desc nulls last,x.id desc limit 1
                ) s on true
                left join findings_packages fp on fp.session_id=s.id
                left join package_versions pv on pv.package_id=fp.id and pv.version=fp.current_version
                where d.organization_id=:organization and d.owner_membership_id=:member and d.state='ACTIVE'
                order by d.last_activity_at desc,m.created_at desc,m.id
                """).param("organization", investigator.organizationId()).param("member", investigator.membershipId())
                .query((rs, row) -> missionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getInt(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8),
                        rs.getString(9), rs.getString(10), rs.getBoolean(11))).list();
    }

    private MissionRow missionRow(UUID id, UUID discoveryId, String discovery, String label, int version,
                                  String missionState, UUID sessionId, String sessionState, String extractionState,
                                  String packageState, boolean activeInvitation) {
        var missionHref = "/app/missions/" + id + "/review";
        if ("DRAFT".equals(missionState)) {
            return new MissionRow(id, discoveryId, discovery, label, version, "Draft needs review", "draft",
                    "Review Mission", missionHref, true, false);
        }
        if (sessionId == null || ("NOT_STARTED".equals(sessionState) && !activeInvitation)) {
            return new MissionRow(id, discoveryId, discovery, label, version, "Approved, ready to invite", "attention",
                    "Invite interviewee", missionHref, true, false);
        }
        if ("NOT_STARTED".equals(sessionState)) {
            return new MissionRow(id, discoveryId, discovery, label, version, "Invitation active", "waiting",
                    "Open Mission", missionHref, false, false);
        }
        if ("ACTIVE".equals(sessionState) || "AWAITING_CONFIRMATION".equals(sessionState)) {
            return new MissionRow(id, discoveryId, discovery, label, version, "Interview in progress", "active",
                    "Open Mission", missionHref, false, true);
        }
        if ("PAUSED".equals(sessionState)) {
            return new MissionRow(id, discoveryId, discovery, label, version, "Interview paused", "waiting",
                    "Open Mission", missionHref, false, true);
        }
        if ("RUNTIME_FAILED".equals(sessionState)) {
            return new MissionRow(id, discoveryId, discovery, label, version, "Interview needs recovery", "attention",
                    "Review recovery", missionHref, true, true);
        }
        if ("TERMINATED".equals(sessionState)) {
            return new MissionRow(id, discoveryId, discovery, label, version, "Interview terminated", "attention",
                    "Open Mission", missionHref, true, false);
        }
        var findingsHref = "/app/findings/" + sessionId;
        if ("FAILED".equals(extractionState)) {
            return new MissionRow(id, discoveryId, discovery, label, version, "Findings extraction failed", "attention",
                    "Retry extraction", findingsHref, true, false);
        }
        if (!"READY".equals(extractionState)) {
            return new MissionRow(id, discoveryId, discovery, label, version, "Findings processing", "waiting",
                    "View progress", findingsHref, false, false);
        }
        if (packageState == null || "READY".equals(packageState)) {
            return new MissionRow(id, discoveryId, discovery, label, version, "Findings ready to review", "attention",
                    "Review findings", findingsHref, true, false);
        }
        var status = "ACCEPTED".equals(packageState) ? "Findings accepted" : "Findings rejected";
        return new MissionRow(id, discoveryId, discovery, label, version, status, "complete",
                "Open findings", findingsHref, false, false);
    }

    private List<FindingsRow> findings(AuthenticatedInvestigator investigator) {
        return db.sql("""
                select s.id,d.title,m.label,p.name,s.extraction_state,pv.state
                from interview_sessions s
                join discoveries d on d.id=s.discovery_id and d.organization_id=s.organization_id
                join missions m on m.id=s.mission_id
                join participants p on p.id=s.participant_id
                left join findings_packages fp on fp.session_id=s.id
                left join package_versions pv on pv.package_id=fp.id and pv.version=fp.current_version
                where d.organization_id=:organization and d.owner_membership_id=:member and d.state='ACTIVE'
                  and s.deleted_at is null and s.state in ('COMPLETED','ENDED_EARLY')
                order by s.completed_at desc nulls last,s.id desc
                """).param("organization", investigator.organizationId()).param("member", investigator.membershipId())
                .query((rs, row) -> findingsRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6))).list();
    }

    private FindingsRow findingsRow(UUID sessionId, String discovery, String mission, String participant,
                                    String extractionState, String packageState) {
        if ("FAILED".equals(extractionState)) return new FindingsRow(sessionId, discovery, mission, participant,
                "Extraction failed", "attention", "Retry extraction");
        if (!"READY".equals(extractionState)) return new FindingsRow(sessionId, discovery, mission, participant,
                "Processing", "waiting", "View progress");
        if (packageState == null || "READY".equals(packageState)) return new FindingsRow(sessionId, discovery, mission,
                participant, "Ready for review", "attention", "Review findings");
        return new FindingsRow(sessionId, discovery, mission, participant,
                "ACCEPTED".equals(packageState) ? "Accepted" : "Rejected", "complete", "Open findings");
    }

    public record DiscoveryRow(UUID id, String title, String objective, long missionCount,
                               long activeInterviewCount, long findingsReadyCount) {}

    public record MissionRow(UUID id, UUID discoveryId, String discovery, String label, int version,
                             String status, String statusTone, String action, String href,
                             boolean needsAttention, boolean interviewActive) {}

    public record FindingsRow(UUID sessionId, String discovery, String mission, String participant,
                              String status, String statusTone, String action) {}
}
