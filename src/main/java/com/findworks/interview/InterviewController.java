package com.findworks.interview;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

@Controller
final class InterviewController {

    private static final String ACCESS_COOKIE = "findworks_interview";
    private final InterviewRepository interviews;
    private final MissionRepository missions;
    private final InvitationRepository invitations;
    private final FindingsRepository findings;
    private final Clock clock;

    InterviewController(InterviewRepository interviews, MissionRepository missions,
            InvitationRepository invitations, FindingsRepository findings, Clock clock) {
        this.interviews = interviews;
        this.missions = missions;
        this.invitations = invitations;
        this.findings = findings;
        this.clock = clock;
    }

    @GetMapping("/missions/{id}")
    String mission(Principal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("mission", missions.mission(id, principal.getName()));
        model.addAttribute("invitation", invitations.forMission(id, principal.getName()));
        model.addAttribute("interviewSession", interviews.missionSession(id, principal.getName()));
        return "mission";
    }

    @PostMapping("/proposals/{id}/confirm")
    String confirm(Principal principal, @PathVariable UUID id) {
        return "redirect:/missions/" + missions.confirmProposal(id, principal.getName());
    }

    @PostMapping("/missions/{id}/save")
    String save(Principal principal, @PathVariable UUID id, @RequestParam MultiValueMap<String, String> form) {
        return "redirect:/missions/" + missions.save(id, principal.getName(), MissionEdit.from(form));
    }

    @PostMapping("/missions/{id}/regenerate")
    String regenerate(Principal principal, @PathVariable UUID id, @RequestParam UUID proposalId,
            @RequestParam String section) {
        return "redirect:/missions/" + missions.regenerate(id, proposalId, section, principal.getName());
    }

    @PostMapping("/missions/{id}/approve")
    String approve(Principal principal, @PathVariable UUID id) {
        missions.approve(id, principal.getName());
        return "redirect:/missions/" + id;
    }

    @PostMapping("/missions/{id}/invitations")
    String sendInvitation(Principal principal, @PathVariable UUID id,
            @RequestParam UUID missionVersionId, @RequestParam String recipientEmail,
            @RequestParam(defaultValue = "false") boolean confirmed) {
        if (!id.equals(missionVersionId)) {
            throw new IllegalArgumentException("The confirmed Mission version is stale.");
        }
        invitations.send(id, recipientEmail, confirmed, principal.getName());
        return "redirect:/missions/" + id;
    }

    @PostMapping("/missions/{missionId}/invitations/{invitationId}/retry")
    String retryInvitation(Principal principal, @PathVariable UUID missionId,
            @PathVariable UUID invitationId) {
        invitations.retry(missionId, invitationId, principal.getName());
        return "redirect:/missions/" + missionId;
    }

    @PostMapping("/missions/{missionId}/invitations/{invitationId}/reissue")
    String reissueInvitation(Principal principal, @PathVariable UUID missionId,
            @PathVariable UUID invitationId) {
        invitations.reissue(missionId, invitationId, principal.getName());
        return "redirect:/missions/" + missionId;
    }

    @GetMapping("/i/{token}")
    String redeem(@PathVariable String token, HttpServletResponse response) {
        var redeemed = interviews.redeem(token);
        var cookie = new Cookie(ACCESS_COOKIE, redeemed.accessToken());
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setPath("/interview");
        cookie.setMaxAge((int) Math.max(0, Duration.between(clock.instant(), redeemed.expiresAt()).toSeconds()));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        privateResponse(response);
        return "redirect:/interview";
    }

    @GetMapping("/interview")
    String interview(@CookieValue(value = ACCESS_COOKIE, required = false) String accessToken, Model model,
            HttpServletResponse response) {
        model.addAttribute("interview", interviews.interview(accessToken));
        privateResponse(response);
        return "interview";
    }

    @PostMapping("/interview/start")
    String start(@CookieValue(value = ACCESS_COOKIE, required = false) String accessToken,
            HttpServletResponse response) {
        interviews.start(accessToken);
        privateResponse(response);
        return "redirect:/interview";
    }

    @PostMapping("/interview/answer")
    String answer(@CookieValue(value = ACCESS_COOKIE, required = false) String accessToken,
            @RequestParam UUID questionId, @RequestParam int expectedRevision, @RequestParam String answer,
            HttpServletResponse response) {
        interviews.answer(accessToken, questionId, expectedRevision, answer);
        privateResponse(response);
        return "redirect:/interview";
    }

    @PostMapping("/interview/choice")
    String answerChoice(@CookieValue(value = ACCESS_COOKIE, required = false) String accessToken,
            @RequestParam UUID questionId, @RequestParam int expectedRevision,
            @RequestParam String choice, @RequestParam(required = false) String owner,
            HttpServletResponse response) {
        interviews.answerChoice(accessToken, questionId, expectedRevision, choice, owner);
        privateResponse(response);
        return "redirect:/interview";
    }

    @PostMapping("/interview/pause")
    String pause(@CookieValue(value = ACCESS_COOKIE, required = false) String accessToken,
            @RequestParam int expectedRevision, HttpServletResponse response) {
        interviews.pause(accessToken, expectedRevision);
        privateResponse(response);
        return "redirect:/interview";
    }

    @PostMapping("/interview/resume")
    String resume(@CookieValue(value = ACCESS_COOKIE, required = false) String accessToken,
            @RequestParam int expectedRevision, HttpServletResponse response) {
        interviews.resume(accessToken, expectedRevision);
        privateResponse(response);
        return "redirect:/interview";
    }

    @PostMapping("/interview/clarify")
    String clarify(@CookieValue(value = ACCESS_COOKIE, required = false) String accessToken,
            @RequestParam UUID questionId, @RequestParam int expectedRevision,
            HttpServletResponse response) {
        interviews.clarify(accessToken, questionId, expectedRevision);
        privateResponse(response);
        return "redirect:/interview";
    }

    @PostMapping("/interview/revise")
    String revise(@CookieValue(value = ACCESS_COOKIE, required = false) String accessToken,
            @RequestParam UUID evidenceId, @RequestParam int expectedRevision, @RequestParam String answer,
            HttpServletResponse response) {
        interviews.revise(accessToken, evidenceId, expectedRevision, answer);
        privateResponse(response);
        return "redirect:/interview";
    }

    @PostMapping("/interview/continue")
    String continueInterview(@CookieValue(value = ACCESS_COOKIE, required = false) String accessToken,
            @RequestParam int expectedRevision, HttpServletResponse response) {
        interviews.continueInterview(accessToken, expectedRevision);
        privateResponse(response);
        return "redirect:/interview";
    }

    @PostMapping("/interview/completion/continue")
    String continueAfterCompletionProposal(
            @CookieValue(value = ACCESS_COOKIE, required = false) String accessToken,
            @RequestParam UUID proposalId, @RequestParam int expectedRevision,
            HttpServletResponse response) {
        interviews.continueAfterCompletionProposal(accessToken, proposalId, expectedRevision);
        privateResponse(response);
        return "redirect:/interview";
    }

    @PostMapping("/interview/completion/finish")
    String finish(@CookieValue(value = ACCESS_COOKIE, required = false) String accessToken,
            @RequestParam UUID proposalId, @RequestParam int expectedRevision,
            HttpServletResponse response) {
        interviews.finish(accessToken, proposalId, expectedRevision);
        privateResponse(response);
        return "redirect:/interview";
    }

    @PostMapping("/interview/end")
    String endEarly(@CookieValue(value = ACCESS_COOKIE, required = false) String accessToken,
            @RequestParam int expectedRevision, @RequestParam(defaultValue = "false") boolean confirmed,
            HttpServletResponse response) {
        interviews.endEarly(accessToken, expectedRevision, confirmed);
        privateResponse(response);
        return "redirect:/interview";
    }

    @PostMapping("/missions/{missionId}/sessions/{sessionId}/terminate")
    String terminate(Principal principal, @PathVariable UUID missionId, @PathVariable UUID sessionId,
            @RequestParam int expectedRevision, @RequestParam(defaultValue = "false") boolean confirmed) {
        if (!confirmed) {
            throw new IllegalArgumentException("Confirm that you want to terminate this Interview Session.");
        }
        interviews.terminate(missionId, sessionId, expectedRevision, principal.getName());
        return "redirect:/missions/" + missionId;
    }
    @GetMapping("/missions/{id}/findings")
    String findings(Principal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("mission", missions.mission(id, principal.getName()));
        model.addAttribute("findings", findings.view(id, principal.getName()));
        return "findings";
    }

    @PostMapping("/missions/{id}/findings/retry")
    String retryFindings(Principal principal, @PathVariable UUID id) {
        findings.retry(id, principal.getName());
        return "redirect:/missions/" + id + "/findings";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String invalid(IllegalArgumentException error, Model model, HttpServletResponse response) {
        model.addAttribute("message", error.getMessage());
        privateResponse(response);
        return "error";
    }

    @ExceptionHandler(InterviewAccessDeniedException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String accessDenied(InterviewAccessDeniedException error, Model model, HttpServletResponse response) {
        interviews.auditDenied();
        model.addAttribute("message", error.getMessage());
        privateResponse(response);
        return "interview-access-error";
    }

    private static void privateResponse(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }
}
