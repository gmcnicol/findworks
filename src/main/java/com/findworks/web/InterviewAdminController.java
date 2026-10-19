package com.findworks.web;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.findworks.platform.security.CurrentInvestigatorService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class InterviewAdminController {
    private static final Set<String> OPEN_STATES = Set.of("NOT_STARTED", "ACTIVE", "PAUSED", "AWAITING_CONFIRMATION", "RUNTIME_FAILED");
    private final JdbcClient db;
    private final CurrentInvestigatorService current;
    private final MissionResumeProjector projector;

    public InterviewAdminController(JdbcClient db, CurrentInvestigatorService current, MissionResumeProjector projector) {
        this.db = db;
        this.current = current;
        this.projector = projector;
    }

    @GetMapping("/app/interviews/{sessionId}")
    String status(@PathVariable UUID sessionId, Authentication authentication, Model model) {
        var investigator = current.require(authentication);
        var session = db.sql("""
                select s.id,s.mission_id,d.id,d.title,m.label,p.name,p.email,s.state,s.extraction_state,
                       count(e.id),max(e.created_at),mv.state,pv.state,
                       exists(select 1 from access_grants g where g.session_id=s.id and g.revoked_at is null and g.expires_at>now())
                         or exists(select 1 from interview_account_access a where a.session_id=s.id and a.revoked_at is null),
                       exists(select 1 from invitations i where i.session_id=s.id and i.state='DELIVERED'
                         and i.redeemed_at is null and i.revoked_at is null and i.expires_at>now()),
                       exists(select 1 from invitations i where i.session_id=s.id and i.state='PENDING'
                         and i.revoked_at is null and i.expires_at>now())
                from interview_sessions s
                join discoveries d on d.id=s.discovery_id and d.organization_id=s.organization_id
                join missions m on m.id=s.mission_id
                join mission_versions mv on mv.mission_id=m.id and mv.version=m.current_version
                join participants p on p.id=s.participant_id
                left join evidence e on e.session_id=s.id and e.participant_id is not null
                left join findings_packages fp on fp.session_id=s.id
                left join package_versions pv on pv.package_id=fp.id and pv.version=fp.current_version
                where s.id=:session and s.deleted_at is null and d.state='ACTIVE'
                  and d.organization_id=:organization and d.owner_membership_id=:member
                group by s.id,d.id,m.id,p.id,mv.state,pv.state
                """).param("session", sessionId).param("organization", investigator.organizationId())
                .param("member", investigator.membershipId())
                .query((rs, row) -> {
                    var snapshot = new MissionResumeProjector.Snapshot(rs.getObject(2, UUID.class), rs.getString(12), 1,
                            sessionId, rs.getString(8), rs.getString(9), rs.getString(13), rs.getBoolean(15), rs.getBoolean(16), rs.getBoolean(14));
                    return new InterviewStatus(sessionId, snapshot.missionId(), rs.getObject(3, UUID.class), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), snapshot.sessionState(), rs.getLong(10),
                            rs.getObject(11, OffsetDateTime.class), snapshot.browserAccessActive(),
                            snapshot.activeInvitation(), snapshot.pendingInvitation(), projector.project(snapshot));
                }).optional().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("investigator", investigator);
        model.addAttribute("interview", session);
        model.addAttribute("canReissue", OPEN_STATES.contains(session.state()));
        model.addAttribute("canTerminate", OPEN_STATES.contains(session.state()) && !session.state().equals("NOT_STARTED"));
        model.addAttribute("findingsAvailable", session.state().equals("COMPLETED"));
        model.addAttribute("explanation", explanation(session));
        return "interview-admin";
    }

    private String explanation(InterviewStatus session) {
        return switch (session.state()) {
            case "NOT_STARTED" -> session.accessActive() ? "The interviewee has opened their invitation and can begin."
                    : session.pendingInvitation() ? "The invitation is queued for delivery."
                    : session.activeInvitation() ? "The invitation has not been redeemed yet."
                    : "No valid invitation or browser access remains. Reissue access to continue.";
            case "PAUSED" -> "The interviewee’s place is saved. They can return to /interview in their authorised browser.";
            case "RUNTIME_FAILED" -> "Accepted answers are safe. The interviewee can retry the failed turn without repeating an answer.";
            case "TERMINATED" -> "Access is revoked. Existing Evidence remains attached to this Discovery.";
            case "COMPLETED" -> "The interview is complete. Continue with the saved findings and follow-up.";
            default -> "The interviewee can return in their authorised browser and continue from the current question.";
        };
    }

    public record InterviewStatus(UUID id, UUID missionId, UUID discoveryId, String discovery, String mission,
                                  String participant, String participantEmail, String state, long answerCount,
                                  OffsetDateTime lastAnswerAt, boolean accessActive, boolean activeInvitation,
                                  boolean pendingInvitation, MissionResumeProjector.ResumeAction resume) {}
}
