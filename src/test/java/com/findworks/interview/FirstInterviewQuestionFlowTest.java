package com.findworks.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.findworks.shaping.ShapingWorker;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {"findworks.shaping.worker-cron=-", "findworks.invitation.worker-cron=-"})
@AutoConfigureMockMvc
@Testcontainers
class FirstInterviewQuestionFlowTest {

    private static final UUID ORGANISATION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID DISCOVERY = UUID.fromString("40000000-0000-0000-0000-000000000023");
    private static final UUID MISSION = UUID.fromString("50000000-0000-0000-0000-000000000023");
    private static final UUID HIGH_ITEM = UUID.fromString("60000000-0000-0000-0000-000000000023");
    private static final UUID LOW_ITEM = UUID.fromString("60000000-0000-0000-0000-000000000024");
    private static final UUID PARTICIPANT = UUID.fromString("70000000-0000-0000-0000-000000000023");
    private static final UUID SESSION = UUID.fromString("90000000-0000-0000-0000-000000000023");
    private static final String GRANT = "browser-grant-23";
    private static final Path CAPTURE = temporary("findworks-interview-prompt-", ".jsonl");
    private static final Path FAKE_PI = fakePi();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void pi(DynamicPropertyRegistry properties) {
        properties.add("findworks.pi.executable", FAKE_PI::toString);
        properties.add("findworks.pi.session-directory", () -> temporary("findworks-interview-sessions-", "").toString());
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ShapingWorker worker;
    @Autowired InterviewRuntimeRepository runtime;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM discoveries").update();
        jdbc.sql("DELETE FROM audit_records").update();
        Files.deleteIfExists(CAPTURE);
        seed();
    }

    @Test
    void beginProducesOneRevisionBoundAdaptiveQuestionAndSafeProgressView() throws Exception {
        var cookie = new Cookie("findworks_interview", GRANT);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Before you begin")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(cookie).with(csrf()))
                .andExpect(status().is3xxRedirection());
        assertThat(state()).isEqualTo("active:1:false");
        assertThat(jdbc.sql("SELECT expected_revision || ':' || status FROM interview_runtime_runs")
                .query(String.class).single()).isEqualTo("1:queued");

        worker.runNext();
        worker.runNext();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(cookie).with(csrf()))
                .andExpect(status().is3xxRedirection());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Mission coverage")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "When a failed card payment occurs, how do you decide whether Support should retry it?")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No areas covered yet")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Retry decision rules")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ownership and exceptions")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Pi"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("runtime"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "PRIVATE-MISSION-CONTEXT"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        HIGH_ITEM.toString()))));

        assertThat(state()).isEqualTo("active:2:true");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_questions").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM interview_application_events").query(Integer.class).single())
                .isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM interview_runtime_runs").query(String.class).single())
                .isEqualTo("committed");
        assertThat(jdbc.sql("SELECT investigation_item_id FROM interview_questions").query(UUID.class).single())
                .isEqualTo(HIGH_ITEM);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL ROLE findworks_application").update();
            jdbc.sql("SELECT set_config('findworks.organisation_id', ?, true)")
                    .param(ORGANISATION.toString()).query(String.class).single();
            jdbc.sql("UPDATE interview_questions SET question = 'changed'").update();
        })).hasRootCauseInstanceOf(java.sql.SQLException.class);

        var projection = Files.readString(CAPTURE);
        assertThat(projection).contains("SHARED-PAYMENT-CONTEXT", "OPENING-GUIDANCE", "Retry definition",
                        HIGH_ITEM.toString(), LOW_ITEM.toString(), MISSION.toString(), SESSION.toString())
                .doesNotContain("PRIVATE-MISSION-CONTEXT", "participant-secret@example.com", GRANT);
        assertThat(projection.lines()).hasSize(1);
    }

    @Test
    void repositoryRejectsStaleForeignOrOutcomeBearingFirstTurnsWithoutPartialState() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(new Cookie("findworks_interview", GRANT)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        var work = runtime.claimNext();
        var good = submission(work);

        assertThatThrownBy(() -> runtime.complete(work, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), 2, List.of(), good.nextAction())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.complete(work, new InterviewRuntimeRepository.Submission(
                UUID.randomUUID(), work.sessionId(), 1, List.of(), good.nextAction())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.complete(work, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), 1,
                List.of(new InterviewRuntimeRepository.OutcomeProposal("unknown", HIGH_ITEM)), good.nextAction())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runtime.complete(work, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), 1, List.of(), new InterviewRuntimeRepository.NextAction(
                        "ask_question", LOW_ITEM, "Wrong frontier?", null, good.nextAction().progress()))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM interview_questions").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT revision FROM interview_sessions").query(Integer.class).single()).isEqualTo(1);
        var event = runtime.complete(work, good);
        assertThat(runtime.complete(work, good)).isEqualTo(event);
        assertThat(jdbc.sql("SELECT count(*) FROM interview_questions").query(Integer.class).single()).isEqualTo(1);
    }

    private InterviewRuntimeRepository.Submission submission(InterviewRuntimeRepository.Work work) {
        return new InterviewRuntimeRepository.Submission(work.id(), work.sessionId(), work.expectedRevision(),
                List.of(), new InterviewRuntimeRepository.NextAction("ask_question", HIGH_ITEM,
                        "How do retry rules work?", null,
                        new InterviewRuntimeRepository.Progress("None", "Retry rules", "Ownership")));
    }

    private String state() {
        return jdbc.sql("SELECT status || ':' || revision || ':' || (active_question_id IS NOT NULL) FROM interview_sessions")
                .query(String.class).single();
    }

    private void seed() {
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
                          'Understand retry choices', 'Document repeatable rules', 'Owns retry decisions',
                          'Rules and exceptions are explicit', '20 minutes', 'Use for this Discovery', now())
                """).params(MISSION, ORGANISATION, DISCOVERY, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_contexts
                    (id, organisation_id, interview_mission_id, position, visibility, content)
                VALUES (gen_random_uuid(), ?, ?, 0, 'shared', 'SHARED-PAYMENT-CONTEXT'),
                       (gen_random_uuid(), ?, ?, 1, 'private', 'PRIVATE-MISSION-CONTEXT')
                """).params(ORGANISATION, MISSION, ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_boundaries
                    (id, organisation_id, interview_mission_id, position, boundary_kind, content)
                VALUES (gen_random_uuid(), ?, ?, 0, 'boundary', 'Only failed payments'),
                       (gen_random_uuid(), ?, ?, 1, 'prohibited_topic', 'No salaries')
                """).params(ORGANISATION, MISSION, ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_terms (id, organisation_id, interview_mission_id, position, term, meaning)
                VALUES (gen_random_uuid(), ?, ?, 0, 'retry', 'Retry definition')
                """).params(ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_opening_questions
                    (id, organisation_id, interview_mission_id, position, question)
                VALUES (gen_random_uuid(), ?, ?, 0, 'OPENING-GUIDANCE')
                """).params(ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO investigation_items (
                    id, organisation_id, interview_mission_id, position, knowledge_gap,
                    opening_question, required, importance, priority, relevant_context
                ) VALUES (?, ?, ?, 0, 'Retry decision rules', NULL, true, 'Consistency', 'high', 'Payments'),
                         (?, ?, ?, 1, 'Ownership and exceptions', NULL, true, 'Accountability', 'low', 'Escalations')
                """).params(HIGH_ITEM, ORGANISATION, MISSION, LOW_ITEM, ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_allowed_outcomes (
                    id, organisation_id, interview_mission_id, investigation_item_id, position, outcome_kind
                ) VALUES (gen_random_uuid(), ?, ?, ?, 0, 'supported_knowledge'),
                         (gen_random_uuid(), ?, ?, ?, 0, 'unknown')
                """).params(ORGANISATION, MISSION, HIGH_ITEM, ORGANISATION, MISSION, LOW_ITEM).update();
        jdbc.sql("""
                INSERT INTO discovery_participants (id, organisation_id, discovery_id, intended_name, email)
                VALUES (?, ?, ?, 'Billing manager', 'participant-secret@example.com')
                """).params(PARTICIPANT, ORGANISATION, DISCOVERY).update();
        jdbc.sql("""
                INSERT INTO interview_sessions (
                    id, organisation_id, discovery_id, interview_mission_id, participant_id
                ) VALUES (?, ?, ?, ?, ?)
                """).params(SESSION, ORGANISATION, DISCOVERY, MISSION, PARTICIPANT).update();
        jdbc.sql("""
                INSERT INTO interview_access_grants (
                    id, organisation_id, interview_session_id, participant_id, token_hash, expires_at
                ) VALUES (gen_random_uuid(), ?, ?, ?, ?, now() + interval '7 days')
                """).params(ORGANISATION, SESSION, PARTICIPANT, hash(GRANT)).update();
    }

    private static Path fakePi() {
        var script = temporary("findworks-fake-interview-pi-", ".py");
        try {
            Files.writeString(script, """
                    #!/usr/bin/env python3
                    import json, sys
                    request = json.loads(sys.stdin.readline())
                    projection = json.loads(request["message"].split("\\n", 1)[1])
                    with open(%s, "a", encoding="utf-8") as capture:
                        capture.write(json.dumps(projection) + "\\n")
                    submission = {
                        "runId": projection["runId"],
                        "sessionId": projection["sessionId"],
                        "expectedRevision": projection["expectedRevision"],
                        "outcomes": [],
                        "nextAction": {
                            "kind": "ask_question",
                            "targetInvestigationItemId": %s,
                            "question": "When a failed card payment occurs, how do you decide whether Support should retry it?",
                            "humanContext": "Think about the most recent case.",
                            "progress": {
                                "covered": "No areas covered yet",
                                "current": "Retry decision rules",
                                "remaining": "Ownership and exceptions"
                            }
                        }
                    }
                    print(json.dumps({"id": request["id"], "type": "response", "command": "prompt", "success": True}))
                    print(json.dumps({"type": "tool_execution_end", "toolCallId": "tool-1",
                        "toolName": "submit_interview_turn", "result": {"content": [],
                        "details": {"submission": submission}}, "isError": False}))
                    print(json.dumps({"type": "agent_settled"}))
                    """.formatted(pythonString(CAPTURE.toString()), pythonString(HIGH_ITEM.toString())));
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
            return script;
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static String pythonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Path temporary(String prefix, String suffix) {
        try {
            return suffix.isEmpty() ? Files.createTempDirectory(prefix) : Files.createTempFile(prefix, suffix);
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
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
