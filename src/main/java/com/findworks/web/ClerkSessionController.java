package com.findworks.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@ConditionalOnProperty(name = "findworks.clerk.enabled", havingValue = "true")
public class ClerkSessionController {
    @GetMapping("/auth/check")
    ResponseEntity<Void> check() {
        // The filter verified the fresh JWT. Discovery membership and interview
        // assignments are checked separately by each product operation.
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
