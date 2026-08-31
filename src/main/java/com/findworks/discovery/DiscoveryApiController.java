package com.findworks.discovery;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/discoveries")
public class DiscoveryApiController {

    private final DiscoveryService discoveryService;

    public DiscoveryApiController(DiscoveryService discoveryService) {
        this.discoveryService = discoveryService;
    }

    @GetMapping
    List<DiscoverySummary> list(Authentication authentication) {
        return discoveryService.listOwned(authentication);
    }
}
