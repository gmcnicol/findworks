package com.findworks.interview;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;

@Controller
final class InterviewController {

    private static final String ACCESS_COOKIE = "findworks_interview";
    private final InterviewRepository interviews;
    private final MissionRepository missions;

    InterviewController(InterviewRepository interviews, MissionRepository missions) {
        this.interviews = interviews;
        this.missions = missions;
    }

    @GetMapping("/missions/{id}")
    String mission(Principal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("mission", missions.mission(id, principal.getName()));
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

    @GetMapping("/i/{token}")
    String redeem(@PathVariable String token, HttpServletResponse response) {
        var redeemed = interviews.redeem(token);
        var cookie = new Cookie(ACCESS_COOKIE, redeemed.accessToken());
        cookie.setHttpOnly(true);
        cookie.setPath("/interview");
        cookie.setMaxAge(7 * 24 * 60 * 60);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        return "redirect:/interview";
    }

    @GetMapping("/interview")
    String interview(@CookieValue(ACCESS_COOKIE) String accessToken, Model model) {
        model.addAttribute("interview", interviews.interview(accessToken));
        return "interview";
    }

    @PostMapping("/interview/answers")
    String answer(
            @CookieValue(ACCESS_COOKIE) String accessToken,
            @RequestParam UUID itemId,
            @RequestParam String answer,
            @RequestHeader(value = "Datastar-Request", defaultValue = "false") boolean datastar,
            Model model) {
        interviews.answer(accessToken, itemId, answer);
        if (datastar) {
            model.addAttribute("interview", interviews.interview(accessToken));
            return "interview :: interview-card";
        }
        return "redirect:/interview";
    }

    @GetMapping("/missions/{id}/findings")
    String findings(Principal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("mission", missions.mission(id, principal.getName()));
        model.addAttribute("findings", interviews.findings(id, principal.getName()));
        return "findings";
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String invalid(IllegalArgumentException error, Model model) {
        model.addAttribute("message", error.getMessage());
        return "error";
    }
}
