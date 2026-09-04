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
            return "redirect:/app";
        }
        model.addAttribute("signedOut", signedOut);
        return "landing";
    }

    @GetMapping("/signin")
    String signIn() {
        return "signin";
    }

    @GetMapping("/denied")
    String denied() {
        return "denied";
    }
}
