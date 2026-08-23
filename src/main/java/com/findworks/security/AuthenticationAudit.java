package com.findworks.security;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class AuthenticationAudit {

    private final PilotTenant tenant;

    AuthenticationAudit(PilotTenant tenant) {
        this.tenant = tenant;
    }

    @EventListener
    @Transactional
    void authenticated(AuthenticationSuccessEvent event) {
        var investigator = tenant.investigator(event.getAuthentication().getName());
        tenant.audit(investigator, "investigator_authenticated", "membership", investigator.membershipId());
    }
}
