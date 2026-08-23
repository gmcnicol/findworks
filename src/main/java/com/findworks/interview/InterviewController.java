package com.findworks.interview;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@Controller
final class InterviewController {

    private static final String ACCESS_COOKIE = "findworks_interview";
    private final InterviewRepository repository;

    InterviewController(InterviewRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/missions/{id}")
    String mission(Principal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("mission", repository.mission(id, principal.getName()));
        return "mission";
    }

    @PostMapping("/missions/{id}/invite")
    String invite(Principal principal, @PathVariable UUID id, Model model) {
        var token = repository.approveAndInvite(id, principal.getName());
        var mission = repository.mission(id, principal.getName());
        var inviteUrl = ServletUriComponentsBuilder.fromCurrentContextPath().path("/i/{token}")
                .buildAndExpand(token).toUriString();
        var subject = encode("A FindWorks interview invitation");
        var body = encode("Hi " + mission.intervieweeName() + ",\n\nPlease use this private link to take part:\n" + inviteUrl);
        model.addAttribute("mission", mission);
        model.addAttribute("inviteUrl", inviteUrl);
        model.addAttribute("mailto", "mailto:" + mission.intervieweeEmail() + "?subject=" + subject + "&body=" + body);
        return "mission";
    }

    @GetMapping("/i/{token}")
    String redeem(@PathVariable String token, HttpServletResponse response) {
        var redeemed = repository.redeem(token);
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
        model.addAttribute("interview", repository.interview(accessToken));
        return "interview";
    }

    @PostMapping("/interview/answers")
    String answer(
            @CookieValue(ACCESS_COOKIE) String accessToken,
            @RequestParam UUID itemId,
            @RequestParam String answer,
            @RequestHeader(value = "Datastar-Request", defaultValue = "false") boolean datastar,
            Model model) {
        repository.answer(accessToken, itemId, answer);
        if (datastar) {
            model.addAttribute("interview", repository.interview(accessToken));
            return "interview :: interview-card";
        }
        return "redirect:/interview";
    }

    @GetMapping("/missions/{id}/findings")
    String findings(Principal principal, @PathVariable UUID id, Model model) {
        model.addAttribute("mission", repository.mission(id, principal.getName()));
        model.addAttribute("findings", repository.findings(id, principal.getName()));
        return "findings";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String invalid(IllegalArgumentException error, Model model) {
        model.addAttribute("message", error.getMessage());
        return "error";
    }
}
