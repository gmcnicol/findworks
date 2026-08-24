package com.findworks.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "findworks.shaping.worker-cron=-",
        "findworks.invitation.worker-cron=-",
        "findworks.invitation.public-origin=https://public.findworks.test",
        "findworks.invitation.sender=interviews@findworks.test",
        "findworks.invitation.active-key-id=test-key-1",
        "findworks.invitation.active-secret=0123456789abcdef0123456789abcdef",
        "findworks.interview.contact=privacy@findworks.test"
})
@AutoConfigureMockMvc
@Testcontainers
@Import(InvitationRedemptionFlowTest.ProviderConfiguration.class)
class InvitationRedemptionFlowTest {

    private static final String INVESTIGATOR = "investigator@findworks.local";
    private static final String SAFE_MESSAGE =
            "This interview access is unavailable. Ask the Investigator for a reissued invitation.";
    private static final UUID ORGANISATION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID DISCOVERY = UUID.fromString("40000000-0000-0000-0000-000000000022");
    private static final UUID MISSION = UUID.fromString("50000000-0000-0000-0000-000000000022");
    private static final UUID ITEM = UUID.fromString("60000000-0000-0000-0000-000000000022");
    private static final UUID OTHER_ORGANISATION = UUID.fromString("10000000-0000-0000-0000-000000000099");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired InvitationRepository invitations;
    @Autowired InvitationDeliveryWorker worker;
    @Autowired RecordingProvider provider;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM evidence").update();
        jdbc.sql("DELETE FROM discoveries").update();
        jdbc.sql("DELETE FROM audit_records").update();
        jdbc.sql("DELETE FROM organisations WHERE id = ?").param(OTHER_ORGANISATION).update();
        provider.deliveries.clear();
        seedMission();
    }

    @Test
    void oneTimeExchangeShowsIntroductionAndRequiresExplicitCsrfProtectedBegin() throws Exception {
        var rawInvitation = deliveredToken();
        assertThat(count("interview_sessions")).isZero();

        var redemption = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/i/{token}", rawInvitation))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/interview"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andReturn();
        var cookie = redemption.getResponse().getCookie("findworks_interview");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/interview");
        assertThat(cookie.getDomain()).isNull();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
        assertThat(cookie.getMaxAge()).isBetween(1, 7 * 24 * 60 * 60);
        assertThat(jdbc.sql("""
                SELECT i.delivery_status = 'redeemed'
                       AND s.interview_mission_id = i.interview_mission_id
                       AND s.participant_id = i.participant_id
                       AND s.discovery_id = i.discovery_id
                       AND s.organisation_id = i.organisation_id
                FROM invitations i JOIN interview_sessions s ON s.interview_mission_id = i.interview_mission_id
                """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT token_hash FROM interview_access_grants").query(String.class).single())
                .isEqualTo(hash(cookie.getValue())).isNotEqualTo(hash(rawInvitation));
        deniedInvitation(rawInvitation);
        assertThat(count("interview_sessions")).isEqualTo(1);
        assertThat(count("interview_access_grants")).isEqualTo(1);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Before you begin")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(INVESTIGATOR)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("FindWorks Pilot")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Understand retry rules")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("20 minutes")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Use for this Discovery")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("90 days")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("privacy@findworks.test")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Begin interview")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("PRIVATE-CONTEXT-MUST-NOT-LEAK"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("QUESTION-LIST-MUST-NOT-LEAK"))));
        assertThat(sessionState()).isEqualTo("not_started:0:false");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(cookie))
                .andExpect(status().isForbidden());
        assertThat(sessionState()).isEqualTo("not_started:0:false");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(cookie).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/interview"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(cookie).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(sessionState()).isEqualTo("active:1:true");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_records WHERE action = 'interview_session_started'")
                .query(Integer.class).single()).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Interview started")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("adaptive question")));
    }

    @Test
    void expiredRevokedReusedMissingAndWrongScopeCredentialsDenyIdentically() throws Exception {
        var rawInvitation = deliveredToken();
        jdbc.sql("""
                UPDATE invitations
                SET send_confirmed_at = now() - interval '8 days', expires_at = now() - interval '1 day'
                """).update();
        deniedInvitation(rawInvitation);
        assertThat(jdbc.sql("SELECT count(*) FROM interview_sessions WHERE organisation_id = ?")
                .param(ORGANISATION).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM interview_access_grants WHERE organisation_id = ?")
                .param(ORGANISATION).query(Integer.class).single()).isZero();

        jdbc.sql("""
                UPDATE invitations
                SET send_confirmed_at = now(), expires_at = now() + interval '7 days',
                    delivery_status = 'revoked', revoked_at = now()
                """).update();
        deniedInvitation(rawInvitation);
        deniedInvitation("unknown-token");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview"))
                .andExpect(status().isBadRequest()).andExpect(content().string(
                        org.hamcrest.Matchers.containsString(SAFE_MESSAGE)));

        var wrongInvitation = "wrong-scope-invitation";
        var wrongGrant = "wrong-scope-browser-grant";
        seedOtherScope(wrongInvitation, wrongGrant);
        deniedInvitation(wrongInvitation);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(new Cookie("findworks_interview", wrongGrant)))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(SAFE_MESSAGE)))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("OTHER-DISCOVERY-MUST-NOT-LEAK"))));
        assertThat(jdbc.sql("SELECT count(*) FROM interview_sessions WHERE organisation_id = ?")
                .param(ORGANISATION).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM interview_access_grants WHERE organisation_id = ?")
                .param(ORGANISATION).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_records WHERE action = 'interview_access_denied'")
                .query(Integer.class).single()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void reissueRestoresLostBrowserAccessAndResumesSameSession() throws Exception {
        var firstRaw = deliveredToken();
        var first = redeem(firstRaw);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(first).with(csrf())).andExpect(status().is3xxRedirection());
        var session = jdbc.sql("SELECT id FROM interview_sessions").query(UUID.class).single();
        var oldInvitation = jdbc.sql("SELECT id FROM invitations").query(UUID.class).single();

        invitations.reissue(MISSION, oldInvitation, INVESTIGATOR);
        worker.runNext();
        var secondRaw = token(provider.deliveries.getLast());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(first))
                .andExpect(status().isBadRequest());
        deniedInvitation(firstRaw);

        var second = redeem(secondRaw);
        assertThat(jdbc.sql("SELECT id FROM interview_sessions").query(UUID.class).single()).isEqualTo(session);
        assertThat(count("interview_sessions")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM interview_sessions").query(String.class).single())
                .isEqualTo("active");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_access_grants WHERE revoked_at IS NULL")
                .query(Integer.class).single()).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(second))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Interview started")));
    }

    private Cookie redeem(String token) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/i/{token}", token))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getCookie("findworks_interview");
    }

    private void deniedInvitation(String token) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/i/{token}", token))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(SAFE_MESSAGE)));
    }

    private String deliveredToken() {
        invitations.send(MISSION, "expert@example.com", true, INVESTIGATOR);
        worker.runNext();
        return token(provider.deliveries.getLast());
    }

    private static String token(InvitationDeliveryProvider.Delivery delivery) {
        return delivery.textBody().lines().filter(line -> line.startsWith("https://")).findFirst().orElseThrow()
                .substring("https://public.findworks.test/i/".length());
    }

    private String sessionState() {
        return jdbc.sql("SELECT status || ':' || revision || ':' || (started_at IS NOT NULL) FROM interview_sessions")
                .query(String.class).single();
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private void seedMission() {
        jdbc.sql("""
                INSERT INTO discoveries (id, organisation_id, owner_membership_id, title, objective)
                VALUES (?, ?, ?, 'Payment failures', 'Learn retry rules')
                """).params(DISCOVERY, ORGANISATION, OWNER).update();
        jdbc.sql("""
                INSERT INTO interview_missions (
                    id, organisation_id, discovery_id, lineage_id, version, status,
                    interviewee_name, interviewee_email, objective, desired_outcome,
                    interviewee_relevance, completion_criteria, expected_commitment,
                    data_use_summary, approved_at
                ) VALUES (?, ?, ?, ?, 1, 'approved', 'Billing manager', NULL,
                          'Understand retry rules', 'Document rules', 'Owns retry decisions',
                          'Rules are explicit', '20 minutes', 'Use for this Discovery', now())
                """).params(MISSION, ORGANISATION, DISCOVERY, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_contexts
                    (id, organisation_id, interview_mission_id, position, visibility, content)
                VALUES (gen_random_uuid(), ?, ?, 0, 'shared', 'Payment status'),
                       (gen_random_uuid(), ?, ?, 1, 'private', 'PRIVATE-CONTEXT-MUST-NOT-LEAK')
                """).params(ORGANISATION, MISSION, ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_boundaries
                    (id, organisation_id, interview_mission_id, position, boundary_kind, content)
                VALUES (gen_random_uuid(), ?, ?, 0, 'boundary', 'Payment handling'),
                       (gen_random_uuid(), ?, ?, 1, 'prohibited_topic', 'No salaries')
                """).params(ORGANISATION, MISSION, ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_opening_questions
                    (id, organisation_id, interview_mission_id, position, question)
                VALUES (gen_random_uuid(), ?, ?, 0, 'QUESTION-LIST-MUST-NOT-LEAK')
                """).params(ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO investigation_items (
                    id, organisation_id, interview_mission_id, position, knowledge_gap,
                    opening_question, required, importance, priority, relevant_context
                ) VALUES (?, ?, ?, 0, 'Retry rules', NULL, true, 'Consistency', 'high', 'Payments')
                """).params(ITEM, ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_allowed_outcomes (
                    id, organisation_id, interview_mission_id, investigation_item_id, position, outcome_kind
                ) VALUES (gen_random_uuid(), ?, ?, ?, 0, 'unknown')
                """).params(ORGANISATION, MISSION, ITEM).update();
    }

    private void seedOtherScope(String invitationToken, String accessToken) {
        var user = UUID.fromString("20000000-0000-0000-0000-000000000099");
        var membership = UUID.fromString("30000000-0000-0000-0000-000000000099");
        var discovery = UUID.fromString("40000000-0000-0000-0000-000000000099");
        var mission = UUID.fromString("50000000-0000-0000-0000-000000000099");
        var participant = UUID.fromString("70000000-0000-0000-0000-000000000099");
        var invitation = UUID.fromString("80000000-0000-0000-0000-000000000099");
        var session = UUID.fromString("90000000-0000-0000-0000-000000000099");
        jdbc.sql("INSERT INTO organisations (id, name) VALUES (?, 'Other Organisation')")
                .param(OTHER_ORGANISATION).update();
        jdbc.sql("INSERT INTO users (id, email, email_verified_at) VALUES (?, 'other-owner@example.com', now())")
                .param(user).update();
        jdbc.sql("INSERT INTO memberships (id, organisation_id, user_id, role) VALUES (?, ?, ?, 'investigator')")
                .params(membership, OTHER_ORGANISATION, user).update();
        jdbc.sql("""
                INSERT INTO discoveries (id, organisation_id, owner_membership_id, title, objective)
                VALUES (?, ?, ?, 'OTHER-DISCOVERY-MUST-NOT-LEAK', 'Other objective')
                """).params(discovery, OTHER_ORGANISATION, membership).update();
        jdbc.sql("""
                INSERT INTO interview_missions (
                    id, organisation_id, discovery_id, lineage_id, status, interviewee_name,
                    interviewee_email, objective, desired_outcome, interviewee_relevance,
                    completion_criteria, expected_commitment, data_use_summary, approved_at
                ) VALUES (?, ?, ?, ?, 'approved', 'Other participant', NULL, 'Other purpose',
                          'Other outcome', 'Other relevance', 'Other completion', '10 minutes',
                          'OTHER-DATA-MUST-NOT-LEAK', now())
                """).params(mission, OTHER_ORGANISATION, discovery, mission).update();
        jdbc.sql("""
                INSERT INTO discovery_participants (id, organisation_id, discovery_id, intended_name, email)
                VALUES (?, ?, ?, 'Other participant', 'other-participant@example.com')
                """).params(participant, OTHER_ORGANISATION, discovery).update();
        jdbc.sql("""
                INSERT INTO invitations (
                    id, organisation_id, discovery_id, interview_mission_id, participant_id,
                    recipient_email, token_key_id, token_hash, send_confirmed_at,
                    delivery_status, expires_at
                ) VALUES (?, ?, ?, ?, ?, 'other-participant@example.com', 'other-key', ?, now(),
                          'provider_accepted', now() + interval '7 days')
                """).params(invitation, OTHER_ORGANISATION, discovery, mission, participant,
                hash(invitationToken)).update();
        jdbc.sql("""
                INSERT INTO interview_sessions (
                    id, organisation_id, discovery_id, interview_mission_id, participant_id
                ) VALUES (?, ?, ?, ?, ?)
                """).params(session, OTHER_ORGANISATION, discovery, mission, participant).update();
        jdbc.sql("""
                INSERT INTO interview_access_grants (
                    id, organisation_id, interview_session_id, participant_id, token_hash, expires_at
                ) VALUES (gen_random_uuid(), ?, ?, ?, ?, now() + interval '7 days')
                """).params(OTHER_ORGANISATION, session, participant, hash(accessToken)).update();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean @Primary
        RecordingProvider recordingProvider() {
            return new RecordingProvider();
        }
    }

    static final class RecordingProvider implements InvitationDeliveryProvider {
        final List<Delivery> deliveries = new ArrayList<>();

        @Override
        public Accepted submit(Delivery delivery) {
            deliveries.add(delivery);
            return new Accepted("provider-message-" + delivery.idempotencyKey());
        }
    }
}
