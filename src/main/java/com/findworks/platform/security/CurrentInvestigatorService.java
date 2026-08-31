package com.findworks.platform.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import com.findworks.platform.config.FindWorksProperties;

@Service
public class CurrentInvestigatorService {

    private final InvestigatorAccountRepository repository;
    private final FindWorksProperties properties;

    public CurrentInvestigatorService(InvestigatorAccountRepository repository, FindWorksProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public AuthenticatedInvestigator require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof InvestigatorUserDetails details)) {
            throw new AccessDeniedException("Authentication required");
        }
        return repository.findActiveByMembershipId(details.investigator().membershipId(), properties.pilotOrganizationSlug())
                .orElseThrow(() -> new AccessDeniedException("Membership is no longer active"));
    }
}
