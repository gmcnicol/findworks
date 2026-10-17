package com.findworks.web;

import com.findworks.platform.config.ClerkProperties;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.ui.Model;

@ControllerAdvice
public class ClerkViewModelAdvice {

    private final ClerkProperties properties;

    public ClerkViewModelAdvice(ClerkProperties properties) {
        this.properties = properties;
    }

    @ModelAttribute
    void clerkConfiguration(Model model) {
        model.addAttribute("clerkEnabled", properties.enabled());
        if (properties.enabled()) {
            model.addAttribute("clerkPublishableKey", properties.publishableKey());
            model.addAttribute("clerkScriptUrl", properties.frontendApi() + "/npm/@clerk/clerk-js@6.37.0/dist/clerk.browser.js");
            model.addAttribute("clerkUiScriptUrl", properties.frontendApi() + "/npm/@clerk/ui@1.38.1/dist/ui.browser.js");
        }
    }
}
