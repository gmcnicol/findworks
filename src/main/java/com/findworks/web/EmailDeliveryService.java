package com.findworks.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.findworks.platform.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Component
class EmailDeliveryService {
    private final JdbcClient db;
    private final TransactionTemplate transactions;
    private final ObjectMapper mapper;
    private final boolean testSupport;
    private final String key;
    private final String endpoint;
    private final String apiKey;
    private final String from;
    private final com.findworks.platform.config.ClerkProperties clerk;
    private final org.springframework.beans.factory.ObjectProvider<ClerkInvitationClient> clerkInvitations;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    EmailDeliveryService(JdbcClient db, TransactionTemplate transactions, ObjectMapper mapper,
                         @Value("${findworks.test-support:false}") boolean testSupport,
                         @Value("${findworks.outbox-key}") String key,
                         @Value("${findworks.email-endpoint}") String endpoint,
                         @Value("${findworks.email-api-key}") String apiKey,
                         @Value("${findworks.email-from}") String from,
                         com.findworks.platform.config.ClerkProperties clerk,
                         org.springframework.beans.factory.ObjectProvider<ClerkInvitationClient> clerkInvitations) {
        this.db = db; this.transactions = transactions; this.mapper = mapper; this.testSupport = testSupport;
        this.key = key; this.endpoint = endpoint; this.apiKey = apiKey; this.from = from;
        this.clerk = clerk; this.clerkInvitations = clerkInvitations;
    }

    boolean invitationDeliveryAvailable() {
        return testSupport || clerk.enabled() || (!endpoint.equals("disabled") && !apiKey.equals("disabled"));
    }

    void queue(UUID invitationId, UUID organizationId, String recipient, String link) {
        if (testSupport) {
            var fail = db.sql("update fault_controls set remaining=remaining-1 where kind='email' and remaining>0").update();
            if (fail == 1) {
                db.sql("update invitations set state='FAILED' where id=:id").param("id", invitationId).update();
                return;
            }
            db.sql("insert into test_emails values(:id,'INVITATION',:recipient,:link,now())")
                    .param("id", Ids.id()).param("recipient", recipient).param("link", link).update();
            db.sql("update invitations set state='DELIVERED',delivered_at=now() where id=:id").param("id", invitationId).update();
            return;
        }
        db.sql("insert into email_outbox(id,organization_id,invitation_id,kind,recipient,encrypted_link,delivery_provider) values(:id,:organization,:invitation,'INVITATION',:recipient,:link,:provider)")
                .param("id", Ids.id()).param("organization", organizationId).param("invitation", invitationId)
                .param("recipient", recipient).param("link", encrypt(link))
                .param("provider", clerk.enabled() ? "CLERK" : "EMAIL").update();
    }

    void queueRetentionWarning(UUID discoveryId, UUID organizationId, String recipient, String link) {
        if (testSupport) {
            db.sql("insert into test_emails values(:id,'RETENTION_WARNING',:recipient,:link,now())")
                    .param("id", Ids.id()).param("recipient", recipient).param("link", link).update();
            return;
        }
        db.sql("insert into email_outbox(id,organization_id,kind,recipient,encrypted_link) values(:id,:organization,'RETENTION_WARNING',:recipient,:link)")
                .param("id", discoveryId).param("organization", organizationId).param("recipient", recipient)
                .param("link", encrypt(link)).update();
    }

    boolean deliverNext() { return deliverNext("EMAIL"); }

    boolean deliverNextClerk() { return deliverNext("CLERK"); }

    private boolean deliverNext(String provider) {
        var job = transactions.execute(status -> {
            db.sql("update email_outbox set state='PENDING',lease_until=null,error_code='PROCESS_DIED' where delivery_provider=:provider and state='RUNNING' and lease_until<now()")
                    .param("provider", provider).update();
            return db.sql("""
                update email_outbox set state='RUNNING',attempts=attempts+1,lease_until=now()+interval '30 seconds'
                where id=(select id from email_outbox where delivery_provider=:provider and state='PENDING' and available_at<=now() order by created_at limit 1 for update skip locked)
                returning id,invitation_id,kind,recipient,encrypted_link,attempts,max_attempts
                """).param("provider", provider).query((rs, row) -> new EmailJob(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                    rs.getString(3), rs.getString(4), rs.getBytes(5), rs.getInt(6), rs.getInt(7))).optional().orElse(null);
        });
        if (job == null) return false;
        try {
            String providerId = null;
            if (job.invitationId() != null) {
                var days = db.sql("select least(:maximumDays,greatest(1,ceil(extract(epoch from (expires_at-now()))/86400)))::int from invitations where id=:id and state='PENDING' and revoked_at is null and expires_at>now()")
                        .param("maximumDays", InvitationPolicy.VALIDITY_DAYS)
                        .param("id", job.invitationId()).query(Integer.class).optional();
                if (days.isEmpty()) {
                    if (provider.equals("CLERK") && job.attempts() > 1) clerkInvitations.getObject().recoverAndRevoke(job.id(), job.recipient());
                    db.sql("update email_outbox set state='CANCELLED',lease_until=null,provider_revoked_at=now(),error_code='INVITATION_INACTIVE' where id=:id")
                            .param("id", job.id()).update();
                    return true;
                }
                if (provider.equals("CLERK")) providerId = clerkInvitations.getObject()
                        .deliver(job.id(), job.recipient(), decrypt(job.encryptedLink()), days.get());
            }
            if (provider.equals("EMAIL")) {
                if (endpoint.equals("disabled") || apiKey.equals("disabled")) throw new IllegalStateException("email_not_configured");
                var body = mapper.writeValueAsString(Map.of("from", from, "to", job.recipient(),
                        "template", job.kind().equals("INVITATION") ? "findworks-invitation" : "findworks-retention-warning",
                        "link", decrypt(job.encryptedLink())));
                var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(15))
                        .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                        .header("Idempotency-Key", job.id().toString())
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var response = http.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("email_provider_rejected");
            }
            var remoteId = providerId;
            transactions.executeWithoutResult(status -> {
                db.sql("update email_outbox set state='COMPLETED',lease_until=null,available_at=now(),error_code=null,provider_invitation_id=:providerId where id=:id")
                        .param("id", job.id()).param("providerId", remoteId).update();
                if (job.invitationId() != null) db.sql("update invitations set state='DELIVERED',delivered_at=now() where id=:id and state='PENDING' and revoked_at is null and expires_at>now()")
                        .param("id", job.invitationId()).update();
            });
        } catch (Exception exception) {
            transactions.executeWithoutResult(status -> {
                var terminal = job.attempts() >= job.maxAttempts();
                db.sql("update email_outbox set state=:state,available_at=now()+(:delay||' seconds')::interval,lease_until=null,error_code='DELIVERY_FAILED' where id=:id")
                        .param("state", terminal ? "FAILED" : "PENDING").param("delay", job.attempts() * 10).param("id", job.id()).update();
                if (terminal && job.invitationId() != null) db.sql("update invitations set state='FAILED' where id=:id and state='PENDING'").param("id", job.invitationId()).update();
            });
        }
        return true;
    }

    boolean revokeNextClerkInvitation() {
        var job = transactions.execute(status -> db.sql("""
                update email_outbox set lease_until=now()+interval '30 seconds'
                where id=(select e.id from email_outbox e join invitations i on i.id=e.invitation_id
                  where e.delivery_provider='CLERK' and e.provider_revoked_at is null
                    and (e.provider_invitation_id is not null or e.attempts>0)
                    and (i.state in ('REVOKED','REDEEMED','FAILED') or i.revoked_at is not null or i.expires_at<=now())
                    and (e.lease_until is null or e.lease_until<now()) and e.available_at<=now()
                  order by e.created_at limit 1 for update of e skip locked)
                returning id,recipient,provider_invitation_id
                """).query((rs, row) -> new RevokeJob(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)))
                .optional().orElse(null));
        if (job == null) return false;
        try {
            if (job.providerId() == null) clerkInvitations.getObject().recoverAndRevoke(job.id(), job.recipient());
            else clerkInvitations.getObject().revoke(job.providerId());
            db.sql("update email_outbox set provider_revoked_at=now(),lease_until=null,error_code=null where id=:id")
                    .param("id", job.id()).update();
        } catch (Exception unavailable) {
            db.sql("update email_outbox set lease_until=null,available_at=now()+interval '60 seconds',error_code='CLERK_REVOCATION_PENDING' where id=:id")
                    .param("id", job.id()).update();
        }
        return true;
    }

    private byte[] encrypt(String value) {
        try {
            var nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(), new GCMParameterSpec(128, nonce));
            var plaintext = value.getBytes(StandardCharsets.UTF_8);
            return ByteBuffer.allocate(nonce.length + cipher.getOutputSize(plaintext.length)).put(nonce).put(cipher.doFinal(plaintext)).array();
        } catch (Exception exception) { throw new IllegalStateException("Configure a valid FINDWORKS_OUTBOX_KEY", exception); }
    }

    private String decrypt(byte[] value) {
        try {
            var buffer = ByteBuffer.wrap(value); var nonce = new byte[12]; buffer.get(nonce); var encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) { throw new IllegalStateException("Unable to decrypt email outbox", exception); }
    }

    private SecretKeySpec secretKey() { return new SecretKeySpec(Base64.getDecoder().decode(key), "AES"); }
    private record EmailJob(UUID id, UUID invitationId, String kind, String recipient, byte[] encryptedLink, int attempts, int maxAttempts) {}
    private record RevokeJob(UUID id, String recipient, String providerId) {}
}
