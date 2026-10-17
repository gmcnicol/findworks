package com.findworks.platform.security;

import com.findworks.platform.config.FindWorksProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "findworks.clerk.enabled", havingValue = "true")
public class ClerkAccountService {
    private final JdbcClient db;
    private final ClerkUserClient clerk;
    private final InvestigatorAccountRepository investigators;
    private final FindWorksProperties properties;

    public ClerkAccountService(JdbcClient db, ClerkUserClient clerk, InvestigatorAccountRepository investigators,
                               FindWorksProperties properties) {
        this.db = db;
        this.clerk = clerk;
        this.investigators = investigators;
        this.properties = properties;
    }

    @org.springframework.transaction.annotation.Transactional
    public void register(String subject) {
        var exists = db.sql("select exists(select 1 from clerk_accounts where clerk_user_id=:subject)")
                .param("subject", subject).query(Boolean.class).single();
        if (exists) return;
        var verified = clerk.requireVerifiedPrimaryEmail(subject);
        db.sql("insert into clerk_accounts(clerk_user_id,verified_email) values(:subject,:email) on conflict do nothing")
                .param("subject", subject).param("email", verified.email()).update();
        // An existing research membership may be linked, but a new Clerk account
        // never creates a membership. Interview access comes from claiming an invitation.
        if (!investigators.hasClerkUserId(subject)) {
            investigators.linkClerkUserByVerifiedEmail(subject, verified.email(), properties.pilotOrganizationSlug());
        }
    }
}
