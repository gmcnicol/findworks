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
    private final MissionWorkQuery missionWork;

    public WorkspaceController(JdbcClient db, CurrentInvestigatorService currentInvestigator,
                               MissionWorkQuery missionWork) {
        this.db = db;
        this.currentInvestigator = currentInvestigator;
        this.missionWork = missionWork;
    }

    @GetMapping("/app")
    String dashboard(Authentication authentication, Model model) {
        var investigator = currentInvestigator.require(authentication);
        var missions = missionWork.listOwned(investigator);
        var discoveries = discoveries(investigator);
        var attention = missions.stream().filter(MissionWorkQuery.MissionWorkItem::needsAttention).limit(8).toList();
        model.addAttribute("investigator", investigator);
        model.addAttribute("attention", attention);
        model.addAttribute("recentMissions", missions.stream().limit(8).toList());
        model.addAttribute("recentDiscoveries", discoveries.stream().limit(4).toList());
        model.addAttribute("discoveryCount", discoveries.size());
        model.addAttribute("missionCount", missions.size());
        model.addAttribute("attentionCount", missions.stream().filter(MissionWorkQuery.MissionWorkItem::needsAttention).count());
        model.addAttribute("activeInterviewCount", missions.stream().filter(MissionWorkQuery.MissionWorkItem::interviewActive).count());
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
        model.addAttribute("missions", missionWork.listOwned(investigator));
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

    public record FindingsRow(UUID sessionId, String discovery, String mission, String participant,
                              String status, String statusTone, String action) {}
}
