package com.findworks.web;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.findworks.platform.config.FindWorksProperties;
import com.findworks.platform.security.ClerkPrincipal;
import com.findworks.platform.security.InvestigatorAccountRepository;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SavedWorkController {
    private final JdbcClient db;
    private final InvestigatorAccountRepository investigators;
    private final FindWorksProperties properties;
    private final MissionWorkQuery missions;

    public SavedWorkController(JdbcClient db, InvestigatorAccountRepository investigators,
                               FindWorksProperties properties, MissionWorkQuery missions) {
        this.db = db;
        this.investigators = investigators;
        this.properties = properties;
        this.missions = missions;
    }

    @GetMapping({"/work", "/interviews"})
    String work(Authentication authentication, Model model, HttpServletResponse response) {
        if (!(authentication.getPrincipal() instanceof ClerkPrincipal principal)) return "redirect:/app";
        response.setHeader("Cache-Control", "no-store");
        var investigator = investigators.findActiveByClerkUserId(principal.subject(), properties.pilotOrganizationSlug());
        model.addAttribute("investigator", investigator.orElse(null));
        model.addAttribute("missions", investigator.map(missions::listOwned).orElse(List.of()));
        model.addAttribute("discoveries", investigator.map(owner -> db.sql("""
                select id,title from discoveries where organization_id=:organization and owner_membership_id=:member and state='ACTIVE'
                order by last_activity_at desc,id
                """).param("organization", owner.organizationId()).param("member", owner.membershipId())
                .query((rs, row) -> new Discovery(rs.getObject(1, UUID.class), rs.getString(2))).list()).orElse(List.of()));
        model.addAttribute("interviews", db.sql("""
                select s.id,mv.objective,s.state,s.last_activity_at
                from interview_account_access a
                join interview_sessions s on s.id=a.session_id and s.organization_id=a.organization_id
                join discoveries d on d.id=s.discovery_id and d.organization_id=s.organization_id
                join mission_versions mv on mv.mission_id=s.mission_id and mv.version=s.mission_version
                where a.clerk_user_id=:subject and a.revoked_at is null and s.deleted_at is null
                  and s.state<>'TERMINATED' and d.state='ACTIVE'
                order by s.last_activity_at desc,s.id
                """).param("subject", principal.subject())
                .query((rs, row) -> new Interview(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class))).list());
        return "saved-work";
    }

    public record Discovery(UUID id, String title) {}
    public record Interview(UUID id, String objective, String state, OffsetDateTime lastActivityAt) {
        public String status() {
            return switch (state) {
                case "NOT_STARTED" -> "Ready to begin";
                case "PAUSED" -> "Paused · your answers are saved";
                case "AWAITING_CONFIRMATION" -> "Ready to finish";
                case "COMPLETED" -> "Completed";
                case "RUNTIME_FAILED" -> "Your answers are saved · retry available";
                default -> "In progress";
            };
        }
        public String action() {
            return switch (state) {
                case "NOT_STARTED" -> "Open interview";
                case "COMPLETED" -> "View completion";
                default -> "Continue interview";
            };
        }
    }
}
