package com.findworks.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@Transactional
class EmailDeliveryServiceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("findworks").withUsername("findworks").withPassword("findworks");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("findworks.outbox-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("findworks.email-endpoint", () -> "disabled");
        registry.add("findworks.email-api-key", () -> "disabled");
    }

    @Autowired EmailDeliveryService email;
    @Autowired JdbcTemplate db;
    @Autowired org.springframework.jdbc.core.simple.JdbcClient jdbc;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired tools.jackson.databind.ObjectMapper mapper;

    private UUID invitation() {
        var organization = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var owner = UUID.fromString("f1111111-1111-1111-1111-111111111111");
        var discovery = UUID.randomUUID(); var mission = UUID.randomUUID(); var participant = UUID.randomUUID();
        var session = UUID.randomUUID(); var invitation = UUID.randomUUID();
        db.update("insert into discoveries(id,organization_id,owner_membership_id,title,objective) values(?,?,?,?,?)",
                discovery, organization, owner, "Outbox test", "Prove delivery recovery");
        db.update("insert into missions(id,organization_id,discovery_id,label,current_version,approved_version) values(?,?,?,?,1,1)",
                mission, organization, discovery, "Outbox test");
        db.update("insert into mission_versions(mission_id,organization_id,version,state,objective,desired_outcome,interviewee_role,interviewee_relevance,data_use,created_by) values(?,?,1,'APPROVED','Test','Test','Tester','Relevant','Test',?)",
                mission, organization, owner);
        db.update("insert into participants values(?,?,?,?,?)", participant, organization, discovery, "Clare", "clare@example.com");
        db.update("insert into interview_sessions(id,organization_id,discovery_id,mission_id,mission_version,participant_id) values(?,?,?,?,1,?)",
                session, organization, discovery, mission, participant);
        db.update("insert into invitations values(?,?,?,?,?,'PENDING',now()+(? * interval '1 second'),null,null,null)",
                invitation, organization, session, "clare@example.com", "0".repeat(64), InvitationPolicy.VALIDITY_SECONDS);
        return invitation;
    }

    @Test
    void invitation_persists_seven_days_as_a_168_hour_duration() {
        var invitation = invitation();

        assertThat(db.queryForObject("select extract(epoch from (expires_at-now()))::bigint from invitations where id=?",
                Long.class, invitation)).isEqualTo(604_800L);
    }

    @Test
    void invitation_link_is_encrypted_and_delivery_stops_after_three_attempts() {
        var organization = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var invitation = invitation();
        var link = "https://findworks.example/invite/secret-token";
        email.queue(invitation, organization, "clare@example.com", link);
        var encrypted = db.queryForObject("select encrypted_link from email_outbox where invitation_id=?", byte[].class, invitation);
        assertThat(new String(encrypted, StandardCharsets.UTF_8)).doesNotContain(link);

        db.update("update email_outbox set state='RUNNING',attempts=1,lease_until=now()-interval '1 second' where invitation_id=?", invitation);
        for (int attempt = 2; attempt <= 3; attempt++) {
            assertThat(email.deliverNext()).isTrue();
            db.update("update email_outbox set available_at=now() where invitation_id=?", invitation);
        }
        assertThat(db.queryForObject("select state from email_outbox where invitation_id=?", String.class, invitation)).isEqualTo("FAILED");
        assertThat(db.queryForObject("select attempts from email_outbox where invitation_id=?", Integer.class, invitation)).isEqualTo(3);
        assertThat(db.queryForObject("select state from invitations where id=?", String.class, invitation)).isEqualTo("FAILED");
    }

    private EmailDeliveryService nativeEmail(ClerkInvitationClient client) {
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory(java.util.Map.of("clerkInvitations", client));
        var properties = new com.findworks.platform.config.ClerkProperties(true, "pk_test_unit", "https://unit.clerk.accounts.dev",
                "https://unit.clerk.accounts.dev", "https://unit.clerk.accounts.dev/.well-known/jwks.json", "sk_test_unit", java.util.List.of("https://findworks.example"));
        return new EmailDeliveryService(jdbc, transactions, mapper, false, Base64.getEncoder().encodeToString(new byte[32]),
                "disabled", "disabled", "FindWorks", properties, beans.getBeanProvider(ClerkInvitationClient.class));
    }

    @Test
    void legacy_retention_warning_rows_remain_inert() {
        var id = UUID.randomUUID();
        db.update("""
                insert into email_outbox(id,organization_id,kind,recipient,encrypted_link,state,lease_until)
                values(?,'11111111-1111-1111-1111-111111111111','RETENTION_WARNING','investigator@example.com',?,'RUNNING',now()-interval '1 minute')
                """, id, new byte[] { 1 });

        assertThat(email.deliverNext()).isFalse();
        assertThat(db.queryForObject("select state from email_outbox where id=?", String.class, id)).isEqualTo("RUNNING");
        assertThat(db.queryForObject("select attempts from email_outbox where id=?", Integer.class, id)).isZero();
        assertThat(db.queryForObject("select error_code from email_outbox where id=?", String.class, id)).isNull();
    }

    @Test
    void clerk_delivery_is_encrypted_and_never_consumed_by_the_legacy_worker() {
        var invitation = invitation();
        var client = mock(ClerkInvitationClient.class);
        when(client.deliver(any(), anyString(), anyString(), anyInt())).thenReturn("inv_native");
        var nativeEmail = nativeEmail(client);
        assertThat(nativeEmail.invitationDeliveryAvailable()).isTrue();
        assertThat(email.invitationDeliveryAvailable()).isFalse();
        var organization = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var link = "https://findworks.example/invite/private-token";
        nativeEmail.queue(invitation, organization, "clare@example.com", link);
        assertThat(db.queryForObject("select delivery_provider from email_outbox where invitation_id=?", String.class, invitation)).isEqualTo("CLERK");
        assertThat(new String(db.queryForObject("select encrypted_link from email_outbox where invitation_id=?", byte[].class, invitation), StandardCharsets.UTF_8)).doesNotContain(link);
        assertThat(email.deliverNext()).isFalse();
        assertThat(nativeEmail.deliverNextClerk()).isTrue();
        assertThat(db.queryForObject("select provider_invitation_id from email_outbox where invitation_id=?", String.class, invitation)).isEqualTo("inv_native");
        assertThat(db.queryForObject("select state from invitations where id=?", String.class, invitation)).isEqualTo("DELIVERED");
        verify(client).deliver(any(UUID.class), eq("clare@example.com"), eq(link), eq(7));
    }

    @Test
    void clerk_delivery_never_extends_the_seven_day_policy_across_autumn_dst() {
        var invitation = invitation();
        db.update("""
                update invitations
                set expires_at=now() + (
                    '2026-10-25 12:00 Europe/London'::timestamptz
                    - '2026-10-18 12:00 Europe/London'::timestamptz
                )
                where id=?
                """, invitation);
        var client = mock(ClerkInvitationClient.class);
        when(client.deliver(any(), anyString(), anyString(), anyInt())).thenReturn("inv_native");
        var nativeEmail = nativeEmail(client);
        nativeEmail.queue(invitation, UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "clare@example.com", "https://findworks.example/invite/private-token");

        assertThat(nativeEmail.deliverNextClerk()).isTrue();

        verify(client).deliver(any(UUID.class), eq("clare@example.com"), anyString(), eq(7));
    }

    @Test
    void clerk_delivery_recovers_an_expired_lease_with_the_same_delivery_identity() {
        var invitation = invitation();
        var client = mock(ClerkInvitationClient.class);
        when(client.deliver(any(), anyString(), anyString(), anyInt())).thenReturn("inv_recovered");
        var nativeEmail = nativeEmail(client);
        nativeEmail.queue(invitation, UUID.fromString("11111111-1111-1111-1111-111111111111"), "clare@example.com", "https://findworks.example/invite/private-token");
        var job = db.queryForObject("select id from email_outbox where invitation_id=?", UUID.class, invitation);
        db.update("update email_outbox set state='RUNNING',attempts=1,lease_until=now()-interval '1 second' where id=?", job);
        assertThat(email.deliverNext()).isFalse();
        assertThat(db.queryForObject("select state from email_outbox where id=?", String.class, job)).isEqualTo("RUNNING");
        assertThat(nativeEmail.deliverNextClerk()).isTrue();
        verify(client).deliver(eq(job), eq("clare@example.com"), anyString(), eq(7));
        assertThat(db.queryForObject("select attempts from email_outbox where id=?", Integer.class, job)).isEqualTo(2);
    }

    @Test
    void revoked_invitation_is_not_sent_or_reactivated() {
        var invitation = invitation();
        var client = mock(ClerkInvitationClient.class);
        var nativeEmail = nativeEmail(client);
        nativeEmail.queue(invitation, UUID.fromString("11111111-1111-1111-1111-111111111111"), "clare@example.com", "https://findworks.example/invite/private-token");
        db.update("update invitations set state='REVOKED',revoked_at=now() where id=?", invitation);
        assertThat(nativeEmail.deliverNextClerk()).isTrue();
        verifyNoInteractions(client);
        assertThat(db.queryForObject("select state from email_outbox where invitation_id=?", String.class, invitation)).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("select state from invitations where id=?", String.class, invitation)).isEqualTo("REVOKED");
    }

    @Test
    void claimed_or_revoked_interview_closes_the_unused_native_account_invitation() {
        var invitation = invitation();
        var client = mock(ClerkInvitationClient.class);
        when(client.deliver(any(), anyString(), anyString(), anyInt())).thenReturn("inv_native");
        var nativeEmail = nativeEmail(client);
        nativeEmail.queue(invitation, UUID.fromString("11111111-1111-1111-1111-111111111111"), "clare@example.com", "https://findworks.example/invite/private-token");
        nativeEmail.deliverNextClerk();
        db.update("update invitations set state='REDEEMED',redeemed_at=now() where id=?", invitation);
        assertThat(nativeEmail.revokeNextClerkInvitation()).isTrue();
        verify(client).revoke("inv_native");
        assertThat(db.queryForObject("select provider_revoked_at is not null from email_outbox where invitation_id=?", Boolean.class, invitation)).isTrue();
        assertThat(nativeEmail.revokeNextClerkInvitation()).isFalse();
    }

    @Test
    void clerk_failure_retries_without_granting_interview_access() {
        var invitation = invitation();
        var client = mock(ClerkInvitationClient.class);
        when(client.deliver(any(), anyString(), anyString(), anyInt())).thenThrow(new IllegalStateException("private-provider-error"));
        var nativeEmail = nativeEmail(client);
        nativeEmail.queue(invitation, UUID.fromString("11111111-1111-1111-1111-111111111111"), "clare@example.com", "https://findworks.example/invite/private-token");
        assertThat(nativeEmail.deliverNextClerk()).isTrue();
        assertThat(db.queryForObject("select state from invitations where id=?", String.class, invitation)).isEqualTo("PENDING");
        assertThat(db.queryForObject("select error_code from email_outbox where invitation_id=?", String.class, invitation)).isEqualTo("DELIVERY_FAILED");
        assertThat(db.queryForObject("select count(*) from interview_account_access a join invitations i on i.session_id=a.session_id where i.id=?", Integer.class, invitation)).isZero();
    }
}
