package com.findworks.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
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
        "findworks.invitation.active-secret=0123456789abcdef0123456789abcdef"
})
@AutoConfigureMockMvc
@Testcontainers
@Import(InvitationFlowTest.ProviderConfiguration.class)
class InvitationFlowTest {

    private static final String EMAIL = "investigator@findworks.local";
    private static final UUID ORGANISATION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID DISCOVERY = UUID.fromString("40000000-0000-0000-0000-000000000021");
    private static final UUID MISSION = UUID.fromString("50000000-0000-0000-0000-000000000021");
    private static final UUID ITEM = UUID.fromString("60000000-0000-0000-0000-000000000021");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired InvitationDeliveryWorker worker;
    @Autowired RecordingProvider provider;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM evidence").update();
        jdbc.sql("DELETE FROM discoveries").update();
        jdbc.sql("DELETE FROM audit_records").update();
        provider.reset();
        jdbc.sql("""
                INSERT INTO discoveries (
                    id, organisation_id, owner_membership_id, title, objective
                ) VALUES (?, ?, ?, 'Payment failures', 'Learn retry rules')
                """).params(DISCOVERY, ORGANISATION, OWNER).update();
        jdbc.sql("""
                INSERT INTO interview_missions (
                    id, organisation_id, discovery_id, lineage_id, version, status,
                    interviewee_name, interviewee_email, objective, desired_outcome,
                    interviewee_relevance, completion_criteria, expected_commitment,
                    data_use_summary, approved_at
                ) VALUES (?, ?, ?, ?, 1, 'approved', 'Billing manager', NULL,
                          'Understand retries', 'Document rules', 'Owns retry decisions',
                          'Rules are explicit', '20 minutes', 'Use for this Discovery', now())
                """).params(MISSION, ORGANISATION, DISCOVERY, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_contexts
                    (id, organisation_id, interview_mission_id, position, visibility, content)
                VALUES (gen_random_uuid(), ?, ?, 0, 'shared', 'Payment status')
                """).params(ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_boundaries
                    (id, organisation_id, interview_mission_id, position, boundary_kind, content)
                VALUES (gen_random_uuid(), ?, ?, 0, 'boundary', 'Payment handling'),
                       (gen_random_uuid(), ?, ?, 1, 'prohibited_topic', 'No salaries')
                """).params(ORGANISATION, MISSION, ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_opening_questions
                    (id, organisation_id, interview_mission_id, position, question)
                VALUES (gen_random_uuid(), ?, ?, 0, 'How are retries decided?')
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

    @Test
    void explicitVersionBoundSendIsDurableSecretAndDuplicateSafe() throws Exception {
        mvc.perform(send(false)).andExpect(status().isBadRequest());
        assertThat(count("invitations")).isZero();

        mvc.perform(send(true)).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/missions/" + MISSION));
        assertThat(provider.deliveries).isEmpty();
        assertThat(count("discovery_participants")).isEqualTo(1);
        assertThat(count("invitations")).isEqualTo(1);
        assertThat(count("invitation_delivery_jobs")).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT interview_mission_id = ? AND recipient_email = 'expert@example.com'
                       AND expires_at = send_confirmed_at + interval '7 days'
                       AND length(token_hash) = 64 AND delivery_status = 'pending'
                FROM invitations
                """).param(MISSION).query(Boolean.class).single()).isTrue();

        mvc.perform(send(true)).andExpect(status().is3xxRedirection());
        assertThat(count("invitations")).isEqualTo(1);
        worker.runNext();
        worker.runNext();
        assertThat(provider.deliveries).hasSize(1);
        var delivery = provider.deliveries.getFirst();
        assertThat(delivery.recipient()).isEqualTo("expert@example.com");
        assertThat(delivery.textBody()).contains("https://public.findworks.test/i/");
        assertThat(delivery.idempotencyKey()).startsWith("invitation-");
        assertThat(jdbc.sql("SELECT delivery_status FROM invitations").query(String.class).single())
                .isEqualTo("provider_accepted");
        assertThat(jdbc.sql("SELECT outcome FROM invitation_delivery_attempts").query(String.class).single())
                .isEqualTo("provider_accepted");

        var token = delivery.textBody().lines().filter(line -> line.startsWith("https://")).findFirst().orElseThrow();
        assertThat(persistedText()).doesNotContain(token).doesNotContain("public.findworks.test/i/");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/missions/{id}", MISSION).with(user(EMAIL).roles("INVESTIGATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Accepted by email provider")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("Delivered to recipient"))));

        addOtherInvestigator();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/missions/{id}/invitations", MISSION)
                        .with(user("other@example.com").roles("INVESTIGATOR")).with(csrf())
                        .param("missionVersionId", MISSION.toString())
                        .param("recipientEmail", "expert@example.com").param("confirmed", "true"))
                .andExpect(status().isForbidden());
    }

    @Test
    void exhaustionManualRetryAndReissuePreserveSessionAndEvidence() throws Exception {
        provider.fail = true;
        mvc.perform(send(true)).andExpect(status().is3xxRedirection());
        worker.runNext();
        worker.runNext();
        worker.runNext();
        worker.runNext();
        assertThat(provider.deliveries).hasSize(3);
        var invitation = jdbc.sql("SELECT id FROM invitations").query(UUID.class).single();
        assertThat(jdbc.sql("SELECT delivery_status FROM invitations WHERE id = ?")
                .param(invitation).query(String.class).single()).isEqualTo("failed");
        assertThat(count("invitation_delivery_attempts")).isEqualTo(3);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/missions/{missionId}/invitations/{invitationId}/retry", MISSION, invitation)
                        .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(count("invitations")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT delivery_cycle || ':' || attempt_count FROM invitation_delivery_jobs")
                .query(String.class).single()).isEqualTo("2:0");
        provider.fail = false;
        worker.runNext();
        assertThat(provider.deliveries).hasSize(4);
        assertThat(provider.deliveries.stream().map(InvitationDeliveryProvider.Delivery::idempotencyKey).distinct())
                .hasSize(1);

        var rawToken = provider.deliveries.getLast().textBody().lines()
                .filter(line -> line.startsWith("https://")).findFirst().orElseThrow()
                .substring("https://public.findworks.test/i/".length());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/i/{token}", rawToken))
                .andExpect(status().is3xxRedirection());
        var session = jdbc.sql("SELECT id FROM interview_sessions WHERE interview_mission_id = ?")
                .param(MISSION).query(UUID.class).single();
        jdbc.sql("""
                INSERT INTO evidence (
                    id, organisation_id, discovery_id, interview_mission_id,
                    interview_session_id, participant_id, investigation_item_id,
                    source_type, answer
                )
                SELECT gen_random_uuid(), organisation_id, discovery_id, interview_mission_id,
                       id, participant_id, ?, 'legacy', 'Existing evidence'
                FROM interview_sessions
                WHERE id = ?
                """).params(ITEM, session).update();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/missions/{missionId}/invitations/{invitationId}/reissue", MISSION, invitation)
                        .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(count("invitations")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT delivery_status FROM invitations WHERE id = ?")
                .param(invitation).query(String.class).single()).isEqualTo("revoked");
        assertThat(jdbc.sql("SELECT id FROM interview_sessions WHERE interview_mission_id = ?")
                .param(MISSION).query(UUID.class).single()).isEqualTo(session);
        assertThat(count("evidence")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM interview_access_grants WHERE revoked_at IS NOT NULL")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void editingApprovedMissionBeforeStartRevokesInvitation() throws Exception {
        mvc.perform(send(true)).andExpect(status().is3xxRedirection());
        var invitation = jdbc.sql("SELECT id FROM invitations").query(UUID.class).single();
        mvc.perform(missionEdit()).andExpect(status().is3xxRedirection());
        assertThat(jdbc.sql("SELECT delivery_status FROM invitations WHERE id = ?")
                .param(invitation).query(String.class).single()).isEqualTo("revoked");
        assertThat(jdbc.sql("SELECT status FROM interview_missions WHERE id = ?")
                .param(MISSION).query(String.class).single()).isEqualTo("superseded");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_missions WHERE status = 'draft'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder send(boolean confirmed) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/missions/{id}/invitations", MISSION)
                .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf())
                .param("missionVersionId", MISSION.toString())
                .param("recipientEmail", "expert@example.com")
                .param("confirmed", Boolean.toString(confirmed));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder missionEdit() {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/missions/{id}/save", MISSION)
                .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf())
                .param("objective", "Changed objective").param("desiredOutcome", "Document rules")
                .param("intendedInterviewee", "Billing manager").param("intervieweeRelevance", "Owns retries")
                .param("sharedContext", "Payment status").param("privateContext", "")
                .param("boundaries", "Payment handling").param("prohibitedTopics", "No salaries")
                .param("terminology", "").param("openingQuestions", "How are retries decided?")
                .param("completionCriteria", "Rules are explicit").param("expectedCommitment", "20 minutes")
                .param("dataUseSummary", "Use for this Discovery").param("itemAction", "keep")
                .param("itemPosition", "1").param("itemGap", "Retry rules")
                .param("itemImportance", "Consistency").param("itemPriority", "high")
                .param("itemContext", "Payments").param("itemRequired", "true")
                .param("itemOutcomes", "unknown").param("ambiguities", "");
    }

    private int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private String persistedText() {
        return jdbc.sql("""
                SELECT concat_ws(' ', i.id, i.token_hash, i.token_key_id, i.recipient_email,
                       j.id, j.status, a.error_class, a.provider_message_id, ar.action)
                FROM invitations i
                LEFT JOIN invitation_delivery_jobs j ON j.invitation_id = i.id
                LEFT JOIN invitation_delivery_attempts a ON a.invitation_id = i.id
                LEFT JOIN audit_records ar ON ar.resource_id = i.id
                """).query(String.class).list().toString();
    }

    private void addOtherInvestigator() {
        jdbc.sql("""
                INSERT INTO users (id, email, email_verified_at)
                VALUES ('20000000-0000-0000-0000-000000000099', 'other@example.com', now())
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO memberships (id, organisation_id, user_id, role)
                VALUES ('30000000-0000-0000-0000-000000000099', ?,
                        '20000000-0000-0000-0000-000000000099', 'investigator')
                ON CONFLICT DO NOTHING
                """).param(ORGANISATION).update();
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
        boolean fail;

        @Override
        public Accepted submit(Delivery delivery) throws Failure {
            deliveries.add(delivery);
            if (fail) {
                throw new Failure("provider_unavailable");
            }
            return new Accepted("provider-message-" + delivery.idempotencyKey());
        }

        void reset() {
            deliveries.clear();
            fail = false;
        }
    }
}
