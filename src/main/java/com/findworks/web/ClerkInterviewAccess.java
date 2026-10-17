package com.findworks.web;

import java.util.Optional;
import java.util.UUID;

import com.findworks.platform.Ids;
import com.findworks.platform.security.ClerkPrincipal;
import com.findworks.platform.security.ClerkUserClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnProperty(name = "findworks.clerk.enabled", havingValue = "true")
public class ClerkInterviewAccess {
    private final JdbcClient db;
    private final ClerkUserClient clerk;
    private final TransactionTemplate transactions;

    public ClerkInterviewAccess(JdbcClient db, ClerkUserClient clerk, TransactionTemplate transactions) {
        this.db = db;
        this.clerk = clerk;
        this.transactions = transactions;
    }

    public static String subject(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof ClerkPrincipal principal) return principal.subject();
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    public Optional<UUID> previouslyClaimed(String invitationToken, String subject) {
        return db.sql("""
                select s.id from invitations i
                join interview_sessions s on s.id=i.session_id and s.organization_id=i.organization_id
                join discoveries d on d.id=s.discovery_id
                join interview_account_access a on a.session_id=s.id
                where i.token_hash=:hash and a.clerk_user_id=:subject and a.revoked_at is null
                  and s.deleted_at is null and s.state<>'TERMINATED' and d.state='ACTIVE'
                """).param("hash", Ids.sha(invitationToken)).param("subject", subject).query(UUID.class).optional();
    }

    public UUID claim(String token, String subject) {
        var existing = previouslyClaimed(token, subject);
        if (existing.isPresent()) return existing.get();
        // Do not trust email claims, stored profile email, or manual-link possession:
        // the currently verified primary Clerk email must match this invitation.
        String email = clerk.requireVerifiedPrimaryEmail(subject).email();
        return transactions.execute(status -> {
            var invitation = db.sql("""
                    select i.id,i.organization_id,i.session_id,i.recipient_email,s.participant_id
                    from invitations i join interview_sessions s on s.id=i.session_id
                    join discoveries d on d.id=s.discovery_id
                    where i.token_hash=:hash and i.state='DELIVERED' and i.redeemed_at is null
                      and i.revoked_at is null and i.expires_at>now()
                      and s.deleted_at is null and s.state not in ('TERMINATED','COMPLETED','ENDED_EARLY') and d.state='ACTIVE'
                    for update of i,s
                    """).param("hash", Ids.sha(token))
                    .query((rs, row) -> new Invitation(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                            rs.getObject(3, UUID.class), rs.getString(4), rs.getObject(5, UUID.class))).optional()
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "This invitation is no longer available"));
            if (!email.equalsIgnoreCase(invitation.email())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Sign in with the email address this invitation was sent to");
            }
            db.sql("insert into interview_account_access(id,organization_id,session_id,clerk_user_id) values(:id,:organization,:session,:subject)")
                    .param("id", Ids.id()).param("organization", invitation.organizationId())
                    .param("session", invitation.sessionId()).param("subject", subject).update();
            db.sql("update invitations set state='REDEEMED',redeemed_at=now() where id=:id").param("id", invitation.id()).update();
            db.sql("""
                    insert into audit_events(id,organization_id,actor_kind,actor_id,action,resource_kind,resource_id,outcome,correlation_id)
                    values(:id,:organization,'INTERVIEWEE',:participant,'INTERVIEW_ACCOUNT_ACCESS_GRANTED','INTERVIEW_SESSION',:session,'SUCCEEDED',:correlation)
                    """).param("id", Ids.id()).param("organization", invitation.organizationId())
                    .param("session", invitation.sessionId()).param("participant", invitation.participantId()).param("correlation", Ids.id()).update();
            return invitation.sessionId();
        });
    }

    private record Invitation(UUID id, UUID organizationId, UUID sessionId, String email, UUID participantId) {}
}
