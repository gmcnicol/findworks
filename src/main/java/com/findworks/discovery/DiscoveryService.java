package com.findworks.discovery;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import com.findworks.platform.security.CurrentInvestigatorService;

@Service
public class DiscoveryService {

    private final DiscoveryRepository repository;
    private final CurrentInvestigatorService currentInvestigatorService;

    public DiscoveryService(DiscoveryRepository repository, CurrentInvestigatorService currentInvestigatorService) {
        this.repository = repository;
        this.currentInvestigatorService = currentInvestigatorService;
    }

    public List<DiscoverySummary> listOwned(Authentication authentication) {
        var investigator = currentInvestigatorService.require(authentication);
        return repository.findActiveOwnedBy(investigator.organizationId(), investigator.membershipId());
    }
}
