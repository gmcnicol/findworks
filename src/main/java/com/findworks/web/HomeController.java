package com.findworks.web;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class HomeController {

    @GetMapping("/")
    String landing(@RequestParam(name = "signed_out", defaultValue = "false") boolean signedOut, Authentication authentication, Model model) {
        if (authentication != null && authentication.isAuthenticated() && !(authentication.getPrincipal() instanceof String)) {
            return "redirect:/work";
        }
        model.addAttribute("signedOut", signedOut);
        return "landing";
    }

    @GetMapping({"/signin", "/signup"})
    String signIn(@RequestParam(defaultValue = "/work") String returnTo,
                  @RequestParam(name = "__clerk_status", defaultValue = "sign_in") String clerkStatus,
                  Model model, jakarta.servlet.http.HttpServletRequest request,
                  jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("signInDestination", SignInDestination.safe(returnTo));
        model.addAttribute("clerkAuthComponent", request.getRequestURI().equals("/signup") || clerkStatus.equals("sign_up")
                ? "sign-up" : "sign-in");
        return "signin";
    }

    @GetMapping("/denied")
    String denied() {
        return "denied";
    }
}
