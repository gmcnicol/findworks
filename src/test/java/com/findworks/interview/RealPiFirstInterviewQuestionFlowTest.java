package com.findworks.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.findworks.shaping.ShapingWorker;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@EnabledIfEnvironmentVariable(named = "RUN_PI_TESTS", matches = "true")
@SpringBootTest(properties = {"findworks.shaping.worker-cron=-", "findworks.invitation.worker-cron=-"})
@AutoConfigureMockMvc
@Testcontainers
class RealPiFirstInterviewQuestionFlowTest {

    private static final UUID ORGANISATION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID DISCOVERY = UUID.fromString("40000000-0000-0000-0000-000000000123");
    private static final UUID MISSION = UUID.fromString("50000000-0000-0000-0000-000000000123");
    private static final UUID ITEM = UUID.fromString("60000000-0000-0000-0000-000000000123");
    private static final UUID PARTICIPANT = UUID.fromString("70000000-0000-0000-0000-000000000123");
    private static final UUID SESSION = UUID.fromString("90000000-0000-0000-0000-000000000123");
    private static final String GRANT = "real-pi-browser-grant";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void pi(DynamicPropertyRegistry properties) {
        properties.add("findworks.pi.session-directory", () -> {
            try {
                return Files.createTempDirectory("findworks-real-interview-pi-").toString();
            } catch (Exception error) {
                throw new ExceptionInInitializerError(error);
            }
        });
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ShapingWorker worker;

    @Test
    void realPiCommitsOneMissionRelevantFirstQuestion() throws Exception {
        seed();
        var cookie = new Cookie("findworks_interview", GRANT);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(cookie).with(csrf()))
                .andExpect(status().is3xxRedirection());

        for (int attempt = 0; attempt < 3 && !"committed".equals(runStatus()); attempt++) {
            worker.runNext();
            jdbc.sql("UPDATE interview_runtime_runs SET available_at = now() WHERE status = 'queued'").update();
        }

        assertThat(runStatus()).isEqualTo("committed");
        var question = jdbc.sql("SELECT question FROM interview_questions").query(String.class).single();
        assertThat(question.toLowerCase()).containsAnyOf("payment", "retry", "history", "rule", "support");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(question)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Mission coverage")));
    }

    private String runStatus() {
        return jdbc.sql("SELECT status FROM interview_runtime_runs").query(String.class).single();
    }

    private void seed() {
        jdbc.sql("""
                INSERT INTO discoveries (id, organisation_id, owner_membership_id, title, objective)
                VALUES (?, ?, ?, 'Payment failures', 'Understand failed payment retry decisions')
                """).params(DISCOVERY, ORGANISATION, OWNER).update();
        jdbc.sql("""
                INSERT INTO interview_missions (
                    id, organisation_id, discovery_id, lineage_id, status, interviewee_name,
                    interviewee_email, objective, desired_outcome, interviewee_relevance,
                    completion_criteria, expected_commitment, data_use_summary, approved_at
                ) VALUES (?, ?, ?, ?, 'approved', 'Billing manager', NULL,
                          'Understand failed payment retry decisions', 'Document consistent retry rules',
                          'Owns retry decisions', 'Rules and exceptions are explicit', '20 minutes',
                          'Use answers for this Discovery', now())
                """).params(MISSION, ORGANISATION, DISCOVERY, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_contexts
                    (id, organisation_id, interview_mission_id, position, visibility, content)
                VALUES (gen_random_uuid(), ?, ?, 0, 'shared',
                        'Support checks account history before retrying a failed card payment.')
                """).params(ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_opening_questions
                    (id, organisation_id, interview_mission_id, position, question)
                VALUES (gen_random_uuid(), ?, ?, 0, 'How does account history affect a retry decision?')
                """).params(ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO investigation_items (
                    id, organisation_id, interview_mission_id, position, knowledge_gap,
                    opening_question, required, importance, priority, relevant_context
                ) VALUES (?, ?, ?, 0, 'When Support retries a failed card payment', NULL, true,
                          'Avoid inconsistent customer treatment', 'high', 'Billing manager checks account history')
                """).params(ITEM, ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_allowed_outcomes (
                    id, organisation_id, interview_mission_id, investigation_item_id, position, outcome_kind
                ) VALUES (gen_random_uuid(), ?, ?, ?, 0, 'supported_knowledge')
                """).params(ORGANISATION, MISSION, ITEM).update();
        jdbc.sql("""
                INSERT INTO discovery_participants (id, organisation_id, discovery_id, intended_name, email)
                VALUES (?, ?, ?, 'Billing manager', 'real-pi-participant@example.com')
                """).params(PARTICIPANT, ORGANISATION, DISCOVERY).update();
        jdbc.sql("""
                INSERT INTO interview_sessions (
                    id, organisation_id, discovery_id, interview_mission_id, participant_id
                ) VALUES (?, ?, ?, ?, ?)
                """).params(SESSION, ORGANISATION, DISCOVERY, MISSION, PARTICIPANT).update();
        jdbc.sql("""
                INSERT INTO interview_access_grants (
                    id, organisation_id, interview_session_id, participant_id, token_hash, expires_at
                ) VALUES (gen_random_uuid(), ?, ?, ?, ?, now() + interval '1 day')
                """).params(ORGANISATION, SESSION, PARTICIPANT, hash(GRANT)).update();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
