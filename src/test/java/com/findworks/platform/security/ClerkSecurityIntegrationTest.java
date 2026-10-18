package com.findworks.platform.security;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.findworks.FindWorksApplication;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(classes = FindWorksApplication.class)
@AutoConfigureMockMvc
@Transactional
class ClerkSecurityIntegrationTest {
    private static final String ISSUER = "https://clerk.findworks.test";
    private static final String ORIGIN = "http://127.0.0.1:8080";
    private static final RSAKey KEY = key();
    private static final HttpServer JWKS = jwksServer();

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("findworks.clerk.enabled", () -> "true");
        registry.add("findworks.test-support", () -> "true");
        registry.add("findworks.clerk.publishable-key", () -> "pk_test_fixture");
        registry.add("findworks.clerk.secret-key", () -> "sk_test_fixture");
        registry.add("findworks.clerk.frontend-api", () -> ISSUER);
        registry.add("findworks.clerk.issuer", () -> ISSUER);
        registry.add("findworks.clerk.jwks-uri", () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
        registry.add("findworks.clerk.authorized-parties", () -> ORIGIN);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @MockitoBean ClerkUserClient clerk;

    @BeforeEach
    void primaryEmail() {
        when(clerk.requireVerifiedPrimaryEmail("user_gareth")).thenReturn(new ClerkUserClient.VerifiedClerkUser("gareth@example.com"));
    }

    @AfterAll
    static void stopServer() { JWKS.stop(0); }

    @Test
    void real_signed_cookie_links_existing_account_and_rechecks_membership_each_request() throws Exception {
        var cookie = new Cookie("__session", token(claims -> {}));
        mvc.perform(get("/app").cookie(cookie)).andExpect(status().isOk()).andExpect(content().string(containsString("Your work")));
        assertThat(db.queryForObject("select clerk_user_id from users where email='gareth@example.com'", String.class)).isEqualTo("user_gareth");
        mvc.perform(get("/auth/check").cookie(cookie)).andExpect(status().isNoContent());
        verify(clerk, times(1)).requireVerifiedPrimaryEmail("user_gareth");
        db.update("update memberships set active=false where user_id='aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'");
        mvc.perform(get("/auth/check").cookie(cookie)).andExpect(status().isNoContent());
        mvc.perform(get("/app").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/work").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void requests_cannot_reuse_a_spring_session_after_the_clerk_token_disappears() throws Exception {
        var result = mvc.perform(get("/app").cookie(new Cookie("__session", token(claims -> {}))))
                .andExpect(status().isOk()).andReturn();
        mvc.perform(get("/app").session((org.springframework.mock.web.MockHttpSession) result.getRequest().getSession()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/signin?returnTo=%2Fapp"));
    }

    @Test
    void expired_wrong_issuer_wrong_party_pending_and_missing_expiration_are_rejected() throws Exception {
        for (Consumer<JWTClaimsSet.Builder> mutation : java.util.List.<Consumer<JWTClaimsSet.Builder>>of(
                c -> c.expirationTime(Date.from(Instant.now().minusSeconds(60))),
                c -> c.issuer("https://attacker.test"), c -> c.claim("azp", "https://attacker.test"),
                c -> c.claim("sts", "pending"), c -> c.expirationTime(null), c -> c.claim("sid", null))) {
            mvc.perform(get("/auth/check").cookie(new Cookie("__session", token(mutation)))).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(clerk);
    }

    @Test
    void signature_tampering_is_rejected() throws Exception {
        var wrongKey = key();
        mvc.perform(get("/auth/check").cookie(new Cookie("__session", sign(claims(), wrongKey))))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(clerk);
    }

    @Test
    void expired_cookie_does_not_poison_sign_in_assets_or_mcp_and_interviews_require_fresh_clerk_auth() throws Exception {
        var cookie = new Cookie("__session", "expired-or-invalid");
        mvc.perform(get("/signin").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/assets/clerk-auth.js").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(post("/mcp").cookie(cookie).header("Authorization", "Bearer fw_opaque_oauth_token")
                        .contentType("application/json").content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", containsString("resource_metadata")));
        mvc.perform(get("/invite/not-a-valid-link").cookie(cookie)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/interview").cookie(cookie)).andExpect(status().is3xxRedirection());
    }

    @Test
    void valid_mcp_oauth_token_is_not_mistaken_for_a_clerk_jwt() throws Exception {
        UUID grant = UUID.randomUUID();
        db.update("insert into oauth_clients(id,name,redirect_uri,scopes) values('client_clerk','Harness','http://localhost:1234/callback','findworks:read')");
        db.update("insert into oauth_grants(id,client_id,membership_id,resource,scopes) values(?,'client_clerk','f1111111-1111-1111-1111-111111111111',?,'findworks:read')", grant, ORIGIN + "/mcp");
        String token = "opaque-findworks-token";
        db.update("insert into oauth_tokens(hash,grant_id,audience,scopes,expires_at) values(?,?,?,'findworks:read',now()+interval '10 minutes')",
                com.findworks.platform.Ids.sha(token), grant, ORIGIN + "/mcp");
        mvc.perform(post("/mcp").header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.tools[0].name").exists());
        verifyNoInteractions(clerk);
    }

    @Test
    void cookie_authenticated_forms_still_require_csrf() throws Exception {
        var cookie = new Cookie("__session", token(c -> {}));
        mvc.perform(post("/oauth/revoke-grant").cookie(cookie).param("id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/oauth/revoke-grant").cookie(cookie).param("id", UUID.randomUUID().toString()).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void sign_in_preserves_mission_and_oauth_destinations_and_rejects_external_redirects() throws Exception {
        mvc.perform(get("/app/missions/123/review")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/signin?returnTo=%2Fapp%2Fmissions%2F123%2Freview"));
        mvc.perform(get("/oauth/authorize").queryParam("client_id", "client_123").queryParam("state", "opaque"))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", containsString("returnTo=%2Foauth%2Fauthorize%3F")));
        mvc.perform(get("/signin").param("returnTo", "https://attacker.test"))
                .andExpect(status().isOk()).andExpect(content().string(containsString("data-sign-in-destination=\"/work\"")));
    }

    @Test
    void interview_only_account_can_sign_in_without_a_research_membership_and_provider_failure_is_retryable() throws Exception {
        when(clerk.requireVerifiedPrimaryEmail("user_unknown")).thenReturn(new ClerkUserClient.VerifiedClerkUser("unknown@example.com"));
        var cookie = new Cookie("__session", token(c -> c.subject("user_unknown")));
        mvc.perform(get("/auth/check").cookie(cookie)).andExpect(status().isNoContent());
        mvc.perform(get("/app").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/work").cookie(cookie)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Interviews you’re answering")));
        assertThat(db.queryForObject("select count(*) from users where clerk_user_id='user_unknown'", Integer.class)).isZero();
        when(clerk.requireVerifiedPrimaryEmail("user_gareth")).thenThrow(new ClerkIdentityUnavailableException());
        mvc.perform(get("/auth/check").cookie(new Cookie("__session", token(c -> {}))))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "5"));
    }

    @Test
    void interviewee_claims_once_then_returns_in_a_new_browser_without_an_invitation_or_grant_cookie() throws Exception {
        var invitation = invitation("sme@example.com");
        var cookie = account("user_sme", "sme@example.com");
        claim(invitation, cookie);
        String base = "/interview/" + invitation.sessionId();
        mvc.perform(post(base + "/begin").cookie(cookie).with(csrf())).andExpect(redirectedUrl(base));
        mvc.perform(post(base + "/answer").cookie(cookie).with(csrf()).param("answer", "A manual exception is approved by the owner."))
                .andExpect(redirectedUrl(base));
        mvc.perform(post("/test/runtime/run-once").param("sessionId", invitation.sessionId().toString())).andExpect(status().isOk());
        mvc.perform(post(base + "/pause").cookie(cookie).with(csrf())).andExpect(redirectedUrl(base));
        mvc.perform(get(base)).andExpect(redirectedUrl("/signin?returnTo=%2Finterview%2F" + invitation.sessionId()));
        // New browser/session: no FindWorks HTTP session, invitation, or Interview Access Grant.
        var newCookie = new Cookie("__session", token(c -> c.subject("user_sme").claim("sid", "sess_new_device")));
        mvc.perform(get("/auth/check").cookie(newCookie)).andExpect(status().isNoContent());
        mvc.perform(get("/work").cookie(newCookie)).andExpect(status().isOk())
                .andExpect(content().string(containsString(base))).andExpect(content().string(containsString("Paused")));
        mvc.perform(get(base).cookie(newCookie)).andExpect(status().isOk())
                .andExpect(content().string(containsString("same account on any device")));
        mvc.perform(post(base + "/resume").cookie(newCookie).with(csrf())).andExpect(redirectedUrl(base));
        mvc.perform(get(base).cookie(newCookie)).andExpect(status().isOk())
                .andExpect(content().string(containsString("A manual exception is approved by the owner.")))
                .andExpect(content().string(containsString(base + "/answer")));
        assertThat(db.queryForObject("select count(*) from evidence where session_id=?", Integer.class, invitation.sessionId())).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from access_grants where session_id=?", Integer.class, invitation.sessionId())).isZero();
        assertThat(db.queryForObject("select count(*) from users where clerk_user_id='user_sme'", Integer.class)).isZero();
    }

    @Test
    void one_clerk_account_can_research_and_answer_multiple_interviews_without_switching_roles() throws Exception {
        var first = invitation("gareth@example.com");
        var second = invitation("gareth@example.com");
        var cookie = account("user_gareth", "gareth@example.com");
        claim(first, cookie);
        claim(second, cookie);
        mvc.perform(get("/work").cookie(cookie)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Research you’re running")))
                .andExpect(content().string(containsString("Interviews you’re answering")))
                .andExpect(content().string(containsString("/interview/" + first.sessionId())))
                .andExpect(content().string(containsString("/interview/" + second.sessionId())))
                .andExpect(content().string(containsString("/app/discoveries/" + first.discoveryId())));
        mvc.perform(get("/app").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/interview/" + first.sessionId()).cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/interview/" + second.sessionId()).cookie(cookie)).andExpect(status().isOk());
        assertThat(db.queryForObject("select count(*) from interview_account_access where clerk_user_id='user_gareth' and revoked_at is null", Integer.class)).isEqualTo(2);
    }

    @Test
    void research_membership_revocation_does_not_erase_independently_invited_interview_access() throws Exception {
        var invitation = invitation("gareth@example.com");
        var cookie = account("user_gareth", "gareth@example.com");
        claim(invitation, cookie);
        db.update("update memberships set active=false where id='f1111111-1111-1111-1111-111111111111'");
        mvc.perform(get("/app").cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/work").cookie(cookie)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Research you’re running"))))
                .andExpect(content().string(containsString("/interview/" + invitation.sessionId())));
        mvc.perform(get("/interview/" + invitation.sessionId()).cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void only_the_server_verified_invited_email_can_claim_and_wrong_account_does_not_consume_the_invitation() throws Exception {
        var invitation = invitation("sme@example.com");
        var wrong = account("user_wrong", "different@example.com");
        mvc.perform(post("/invite/" + invitation.token()).cookie(wrong).with(csrf()))
                .andExpect(status().isForbidden()).andExpect(content().string(containsString("Use a different account")));
        assertThat(db.queryForObject("select state from invitations where token_hash=?", String.class, com.findworks.platform.Ids.sha(invitation.token()))).isEqualTo("DELIVERED");
        assertThat(db.queryForObject("select count(*) from interview_account_access where session_id=?", Integer.class, invitation.sessionId())).isZero();
        claim(invitation, account("user_sme", "sme@example.com"));
    }

    @Test
    void invitation_claim_rechecks_current_verified_email_not_the_cached_registration_email() throws Exception {
        var invitation = invitation("sme@example.com");
        var cookie = account("user_sme", "sme@example.com");
        mvc.perform(get("/auth/check").cookie(cookie)).andExpect(status().isNoContent());
        when(clerk.requireVerifiedPrimaryEmail("user_sme")).thenReturn(new ClerkUserClient.VerifiedClerkUser("changed@example.com"));
        mvc.perform(post("/invite/" + invitation.token()).cookie(cookie).with(csrf())).andExpect(status().isForbidden());
        assertThat(db.queryForObject("select count(*) from interview_account_access where session_id=?", Integer.class, invitation.sessionId())).isZero();
    }

    @Test
    void provider_failure_during_claim_is_retryable_and_does_not_consume_the_invitation() throws Exception {
        var invitation = invitation("sme@example.com");
        var cookie = account("user_sme", "sme@example.com");
        mvc.perform(get("/auth/check").cookie(cookie)).andExpect(status().isNoContent());
        when(clerk.requireVerifiedPrimaryEmail("user_sme")).thenThrow(new ClerkIdentityUnavailableException());
        mvc.perform(post("/invite/" + invitation.token()).cookie(cookie).with(csrf()))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "5"))
                .andExpect(content().string(containsString("saved work are unchanged")));
        assertThat(db.queryForObject("select state from invitations where token_hash=?", String.class, com.findworks.platform.Ids.sha(invitation.token()))).isEqualTo("DELIVERED");
        assertThat(db.queryForObject("select count(*) from interview_account_access where session_id=?", Integer.class, invitation.sessionId())).isZero();
        doReturn(new ClerkUserClient.VerifiedClerkUser("sme@example.com")).when(clerk).requireVerifiedPrimaryEmail("user_sme");
        claim(invitation, cookie);
    }

    @Test
    void another_account_cannot_read_answers_submit_answers_fetch_fragments_or_subscribe_to_an_interview() throws Exception {
        var invitation = invitation("sme@example.com");
        var owner = account("user_sme", "sme@example.com");
        claim(invitation, owner);
        String base = "/interview/" + invitation.sessionId();
        mvc.perform(post(base + "/begin").cookie(owner).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post(base + "/answer").cookie(owner).with(csrf()).param("answer", "Private answer"))
                .andExpect(status().is3xxRedirection());
        var other = account("user_other", "other@example.com");
        for (String suffix : new String[] {"", "/state", "/events"}) {
            mvc.perform(get(base + suffix).cookie(other)).andExpect(status().isForbidden())
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("Private answer"))));
        }
        mvc.perform(post(base + "/answer").cookie(other).with(csrf()).param("answer", "Impersonated answer")).andExpect(status().isForbidden());
        mvc.perform(get("/work").cookie(other)).andExpect(content().string(org.hamcrest.Matchers.not(containsString(base))));
        assertThat(db.queryForObject("select count(*) from evidence where session_id=?", Integer.class, invitation.sessionId())).isEqualTo(1);
    }

    @Test
    void claimed_account_access_survives_invitation_expiry_and_cannot_be_transferred_by_email_change() throws Exception {
        var invitation = invitation("sme@example.com");
        var cookie = account("user_sme", "sme@example.com");
        claim(invitation, cookie);
        db.update("update invitations set expires_at=now()-interval '1 day' where session_id=?", invitation.sessionId());
        when(clerk.requireVerifiedPrimaryEmail("user_sme")).thenReturn(new ClerkUserClient.VerifiedClerkUser("new-email@example.com"));
        mvc.perform(get("/interview/" + invitation.sessionId()).cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/invite/" + invitation.token()).cookie(cookie)).andExpect(redirectedUrl("/interview/" + invitation.sessionId()));
        var replacement = account("user_replacement", "sme@example.com");
        mvc.perform(post("/invite/" + invitation.token()).cookie(replacement).with(csrf())).andExpect(status().isGone());
        mvc.perform(get("/interview/" + invitation.sessionId()).cookie(replacement)).andExpect(status().isForbidden());
    }

    @Test
    void expired_unclaimed_invitation_and_legacy_browser_grant_cannot_bypass_clerk() throws Exception {
        var invitation = invitation("sme@example.com");
        var cookie = account("user_sme", "sme@example.com");
        db.update("update invitations set expires_at=now()-interval '1 day' where session_id=?", invitation.sessionId());
        mvc.perform(post("/invite/" + invitation.token()).cookie(cookie).with(csrf())).andExpect(status().isGone());
        db.update("insert into access_grants(id,organization_id,session_id,token_hash,expires_at) values(?, '11111111-1111-1111-1111-111111111111',?,?,now()+interval '168 hours')",
                UUID.randomUUID(), invitation.sessionId(), com.findworks.platform.Ids.sha("old-browser-token"));
        mvc.perform(get("/interview/" + invitation.sessionId()).cookie(new Cookie("findworks_interview", "old-browser-token")))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/interview/" + invitation.sessionId()).cookie(cookie).cookie(new Cookie("findworks_interview", "old-browser-token")))
                .andExpect(status().isForbidden());
    }

    @Test
    void investigator_revocation_removes_account_access_without_losing_accepted_evidence() throws Exception {
        var invitation = invitation("sme@example.com");
        var cookie = account("user_sme", "sme@example.com");
        claim(invitation, cookie);
        String base = "/interview/" + invitation.sessionId();
        mvc.perform(post(base + "/begin").cookie(cookie).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post(base + "/answer").cookie(cookie).with(csrf()).param("answer", "Retained evidence"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(post("/app/interviews/" + invitation.sessionId() + "/revoke-access")
                .cookie(account("user_gareth", "gareth@example.com")).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(get(base).cookie(cookie)).andExpect(status().isForbidden());
        mvc.perform(get("/work").cookie(cookie)).andExpect(content().string(org.hamcrest.Matchers.not(containsString(base))));
        assertThat(db.queryForObject("select count(*) from evidence where session_id=?", Integer.class, invitation.sessionId())).isEqualTo(1);
        assertThat(db.queryForObject("select count(*) from interview_account_access where session_id=? and revoked_at is null", Integer.class, invitation.sessionId())).isZero();
    }

    @Test
    void deletion_and_termination_revoke_account_access_immediately() throws Exception {
        var cookie = account("user_sme", "sme@example.com");
        var deleted = invitation("sme@example.com");
        var terminated = invitation("sme@example.com");
        var discoveryDeleted = invitation("sme@example.com");
        claim(deleted, cookie); claim(terminated, cookie); claim(discoveryDeleted, cookie);
        db.update("update interview_sessions set deleted_at=now() where id=?", deleted.sessionId());
        db.update("update interview_sessions set state='TERMINATED' where id=?", terminated.sessionId());
        db.update("update discoveries set state='DELETING' where id=?", discoveryDeleted.discoveryId());
        for (var invitation : java.util.List.of(deleted, terminated, discoveryDeleted)) {
            mvc.perform(get("/interview/" + invitation.sessionId()).cookie(cookie)).andExpect(status().isForbidden());
            assertThat(db.queryForObject("select count(*) from interview_account_access where session_id=? and revoked_at is null", Integer.class, invitation.sessionId())).isZero();
        }
    }

    private Cookie account(String subject, String email) throws Exception {
        when(clerk.requireVerifiedPrimaryEmail(subject)).thenReturn(new ClerkUserClient.VerifiedClerkUser(email));
        return new Cookie("__session", token(c -> c.subject(subject)));
    }

    private void claim(InterviewInvitation invitation, Cookie cookie) throws Exception {
        mvc.perform(post("/invite/" + invitation.token()).cookie(cookie).with(csrf()))
                .andExpect(redirectedUrl("/interview/" + invitation.sessionId()))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    private InterviewInvitation invitation(String email) {
        UUID discovery = UUID.randomUUID(), mission = UUID.randomUUID(), participant = UUID.randomUUID(), session = UUID.randomUUID();
        UUID organization = UUID.fromString("11111111-1111-1111-1111-111111111111");
        db.update("insert into discoveries(id,organization_id,owner_membership_id,title,objective) values(?,?,'f1111111-1111-1111-1111-111111111111','Private research title','Private research objective')", discovery, organization);
        db.update("insert into missions(id,organization_id,discovery_id,label,current_version,approved_version) values(?,?,?,'Continuity Mission',1,1)", mission, organization, discovery);
        db.update("insert into mission_versions(mission_id,organization_id,version,state,objective,desired_outcome,interviewee_role,interviewee_relevance,data_use) values(?,?,1,'APPROVED','Understand exception handling','Understand the process','Owner','Knows the exceptions','Research findings')", mission, organization);
        db.update("insert into investigation_items(id,mission_id,version,position,gap,why,priority,required,context,sufficient) values(?,?,1,0,'Who approves exceptions?','Find the owner','HIGH',true,'Exception handling','A specific example')", UUID.randomUUID(), mission);
        db.update("insert into mission_entries(mission_id,version,kind,position,value) values(?,1,'OPENING_QUESTION',0,'How are exceptions approved?')", mission);
        db.update("insert into participants(id,organization_id,discovery_id,name,email) values(?,?,?,'Interviewee',?)", participant, organization, discovery, email);
        db.update("insert into interview_sessions(id,organization_id,discovery_id,mission_id,mission_version,participant_id) values(?,?,?,?,1,?)", session, organization, discovery, mission, participant);
        String token = com.findworks.platform.Ids.token();
        db.update("insert into invitations values(?,?,?,?,?,'DELIVERED',now()+interval '168 hours',now(),null,null)", UUID.randomUUID(), organization, session, email, com.findworks.platform.Ids.sha(token));
        return new InterviewInvitation(session, mission, discovery, token);
    }

    private record InterviewInvitation(UUID sessionId, UUID missionId, UUID discoveryId, String token) {}

    private static JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("user_gareth").claim("sid", "sess_fixture")
                .claim("azp", ORIGIN).issueTime(Date.from(Instant.now()))
                .notBeforeTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(Instant.now().plusSeconds(60)));
    }

    private static String token(Consumer<JWTClaimsSet.Builder> mutation) throws Exception {
        var claims = claims();
        mutation.accept(claims);
        return sign(claims, KEY);
    }

    private static String sign(JWTClaimsSet.Builder claims, RSAKey key) throws Exception {
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).type(JOSEObjectType.JWT).build(), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey key() {
        try { return new RSAKeyGenerator(2048).keyID("fixture").generate(); }
        catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }

    private static HttpServer jwksServer() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                byte[] body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
}
