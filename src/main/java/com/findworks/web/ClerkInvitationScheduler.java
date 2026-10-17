package com.findworks.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Clerk credentials stay in the web process, never the runtime worker or Pi children. */
@Component
@ConditionalOnExpression("'${findworks.process-mode:web}' != 'worker' and ${findworks.clerk.enabled:false}")
@ConditionalOnProperty(name = "findworks.test-support", havingValue = "false", matchIfMissing = true)
class ClerkInvitationScheduler {
    private final EmailDeliveryService email;

    ClerkInvitationScheduler(EmailDeliveryService email) { this.email = email; }

    @Scheduled(fixedDelayString = "${findworks.clerk.invitation-poll-ms:500}")
    void poll() {
        if (!email.revokeNextClerkInvitation()) email.deliverNextClerk();
    }
}
