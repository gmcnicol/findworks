package com.findworks.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.findworks.shaping.ShapingWorker;
import com.findworks.retention.RetentionRepository;
import com.findworks.runtime.InterviewTurnRunner;
import com.findworks.runtime.FindingsExtractionRunner;
import com.findworks.runtime.RuntimeFailure;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
    private static final UUID SECOND_MISSION = UUID.fromString("50000000-0000-0000-0000-000000000025");
    private static final UUID SECOND_ITEM = UUID.fromString("60000000-0000-0000-0000-000000000025");
    private static final UUID SECOND_PARTICIPANT = UUID.fromString("70000000-0000-0000-0000-000000000025");
    private static final UUID SECOND_SESSION = UUID.fromString("90000000-0000-0000-0000-000000000025");
    private static final String GRANT = "browser-grant-23";
    private static final String SECOND_GRANT = "browser-grant-25";
    private static final Path CAPTURE = temporary("findworks-interview-prompt-", ".jsonl");
    private static final Path FAKE_PI = fakePi();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void pi(DynamicPropertyRegistry properties) {
        properties.add("findworks.runtime.enabled", () -> "true");
        properties.add("findworks.runtime.executable", FAKE_PI::toString);
        properties.add("findworks.runtime.image", () -> "findworks/pi@sha256:" + "0".repeat(64));
        properties.add("findworks.runtime.network", () -> "none");
        properties.add("findworks.runtime.provider-credential", () -> "fake-provider-credential");
        properties.add("findworks.runtime.checkpoint-key-id", () -> "test-key");
        properties.add("findworks.runtime.checkpoint-key",
                () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ShapingWorker worker;
    @Autowired InterviewRuntimeRepository runtime;
    @Autowired InterviewTurnRunner interviewRunner;
    @Autowired InterviewRepository interviews;
    @Autowired FindingsRepository findings;
    @Autowired FindingsExtractionRunner findingsRunner;
    @Autowired RetentionRepository retention;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM discoveries").update();
        jdbc.sql("DELETE FROM audit_records").update();
        jdbc.sql("UPDATE organisations SET retention_days = 90 WHERE id = ?")
                .param(ORGANISATION).update();
        Files.deleteIfExists(CAPTURE);
        seed();
    }

    @Test
    void answerCommitsEvidenceBeforeAnAnswerDependentFollowUp() throws Exception {
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("I do not know")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("I prefer not to answer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Someone else knows")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Please clarify")))
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

        var firstQuestion = jdbc.sql("SELECT id FROM interview_questions").query(UUID.class).single();
        var exactAnswer = "North Star rule applies.\nSupport checks three failed attempts before escalation.";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/answer")
                        .cookie(cookie).with(csrf())
                        .param("questionId", firstQuestion.toString()).param("expectedRevision", "2")
                        .param("answer", "  " + exactAnswer + "  "))
                .andExpect(status().is3xxRedirection());

        assertThat(state()).isEqualTo("active:3:false");
        assertThat(jdbc.sql("""
                SELECT e.answer = ? AND e.source_type = 'interviewee_answer'
                    AND e.organisation_id = ? AND e.discovery_id = ? AND e.interview_mission_id = ?
                    AND e.interview_session_id = ? AND e.participant_id = ? AND e.question_id = ?
                    AND e.investigation_item_id = ? AND e.created_at IS NOT NULL
                FROM evidence e
                """).params(exactAnswer, ORGANISATION, DISCOVERY, MISSION, SESSION, PARTICIPANT,
                firstQuestion, HIGH_ITEM).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT trigger || ':' || expected_revision || ':' || status FROM interview_runtime_runs ORDER BY created_at")
                .query(String.class).list()).containsExactly("session_start:1:committed", "accepted_evidence:3:queued");
        assertThat(jdbc.sql("SELECT status FROM investigation_results").query(String.class).single())
                .isEqualTo("exploring");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Answer saved")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your response is safe")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(exactAnswer)));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/answer")
                        .cookie(cookie).with(csrf())
                        .param("questionId", firstQuestion.toString()).param("expectedRevision", "2")
                        .param("answer", exactAnswer))
                .andExpect(status().is3xxRedirection());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/answer")
                        .cookie(cookie).with(csrf())
                        .param("questionId", firstQuestion.toString()).param("expectedRevision", "2")
                        .param("answer", "Different replay"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM interview_runtime_runs").query(Integer.class).single()).isEqualTo(2);

        worker.runNext();
        worker.runNext();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "You mentioned the North Star rule. How do the three failed attempts change escalation?")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"1\" max=\"2\"")));
        assertThat(state()).isEqualTo("active:4:true");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_questions").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM interview_application_events").query(Integer.class).single())
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT covered_count FROM interview_application_events ORDER BY created_at DESC LIMIT 1")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_records WHERE action = 'interview_answer_accepted'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL ROLE findworks_application").update();
            jdbc.sql("SELECT set_config('findworks.organisation_id', ?, true)")
                    .param(ORGANISATION.toString()).query(String.class).single();
            jdbc.sql("UPDATE evidence SET answer = 'changed'").update();
        })).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL ROLE findworks_application").update();
            jdbc.sql("SELECT set_config('findworks.organisation_id', ?, true)")
                    .param(ORGANISATION.toString()).query(String.class).single();
            jdbc.sql("DELETE FROM evidence").update();
        })).hasRootCauseInstanceOf(java.sql.SQLException.class);

        var projection = Files.readString(CAPTURE);
        assertThat(projection).contains("SHARED-PAYMENT-CONTEXT", "OPENING-GUIDANCE", "Retry definition",
                        HIGH_ITEM.toString(), LOW_ITEM.toString(), MISSION.toString(), SESSION.toString(),
                        "North Star rule applies.\\nSupport checks three failed attempts before escalation.",
                        "accepted_evidence", "exploring")
                .doesNotContain("PRIVATE-MISSION-CONTEXT", "participant-secret@example.com", GRANT,
                        "fake-provider-credential", "SPRING_DATASOURCE", "JDBC_DATABASE");
        assertThat(projection.lines()).hasSize(2);
    }

    @Test
    void invalidAnswerOrPostCommitWorkerFailureNeverLosesOrDuplicatesEvidence() throws Exception {
        var cookie = new Cookie("findworks_interview", GRANT);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(cookie).with(csrf())).andExpect(status().is3xxRedirection());
        worker.runNext();
        var question = jdbc.sql("SELECT id FROM interview_questions").query(UUID.class).single();

        assertThatThrownBy(() -> interviews.answer(GRANT, question, 1, "stale"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> interviews.answer(GRANT, UUID.randomUUID(), 2, "foreign"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> interviews.answer(GRANT, question, 2, " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> interviews.answer(GRANT, question, 2, "x".repeat(10_001)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single()).isZero();
        assertThat(state()).isEqualTo("active:2:true");

        jdbc.sql("""
                CREATE FUNCTION fail_accepted_evidence_run() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.trigger = 'accepted_evidence' THEN RAISE EXCEPTION 'synthetic crash'; END IF;
                    RETURN NEW;
                END $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER fail_accepted_evidence_run BEFORE INSERT ON interview_runtime_runs
                FOR EACH ROW EXECUTE FUNCTION fail_accepted_evidence_run()
                """).update();
        try {
            assertThatThrownBy(() -> interviews.answer(GRANT, question, 2, "Durable answer"))
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);
        } finally {
            jdbc.sql("DROP TRIGGER fail_accepted_evidence_run ON interview_runtime_runs").update();
            jdbc.sql("DROP FUNCTION fail_accepted_evidence_run()").update();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM investigation_results").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT answered_at IS NULL FROM interview_questions").query(Boolean.class).single())
                .isTrue();
        assertThat(state()).isEqualTo("active:2:true");

        interviews.answer(GRANT, question, 2, "Durable answer");
        assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM interview_runtime_runs WHERE status = 'queued'")
                .query(Integer.class).single()).isEqualTo(1);
        var failedWork = runtime.claimNext();
        runtime.fail(failedWork, "test-runtime",
                new RuntimeFailure(RuntimeFailure.Kind.TRANSIENT_MODEL, 1));
        assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM interview_runtime_runs ORDER BY created_at DESC LIMIT 1")
                .query(String.class).single()).isEqualTo("queued");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview").cookie(cookie))
                .andExpect(status().isOk()).andExpect(content().string(
                        org.hamcrest.Matchers.containsString("Answer saved")));
        jdbc.sql("UPDATE interview_runtime_runs SET available_at = now() WHERE status = 'queued'").update();
        worker.runNext();
        assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single()).isEqualTo(1);
    }

    @ParameterizedTest
    @MethodSource("unknownResponses")
    void uncertaintyBecomesAnEvidenceLinkedUnknown(String choice, String answer, String reason) throws Exception {
        var question = beginQuestion();
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post(choice == null ? "/interview/answer" : "/interview/choice")
                .cookie(new Cookie("findworks_interview", GRANT)).with(csrf())
                .param("questionId", question.toString()).param("expectedRevision", "2");
        if (choice == null) {
            request.param("answer", answer);
        } else {
            request.param("choice", choice);
        }
        mvc.perform(request).andExpect(status().is3xxRedirection());
        worker.runNext();

        assertThat(jdbc.sql("SELECT reason FROM unknown_outcomes").query(String.class).single())
                .isEqualTo(reason);
        assertThat(jdbc.sql("SELECT count(*) FROM investigation_outcome_evidence")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM investigation_results WHERE investigation_item_id = ?")
                .param(HIGH_ITEM).query(String.class).single()).isEqualTo("explicit_outcome");
        assertThat(jdbc.sql("SELECT investigation_item_id FROM interview_questions ORDER BY sequence DESC LIMIT 1")
                .query(UUID.class).single()).isEqualTo(LOW_ITEM);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL ROLE findworks_application").update();
            jdbc.sql("SELECT set_config('findworks.organisation_id', ?, true)")
                    .param(ORGANISATION.toString()).query(String.class).single();
            jdbc.sql("UPDATE investigation_outcomes SET kind = 'unknown'").update();
        })).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    static Stream<Arguments> unknownResponses() {
        return Stream.of(
                Arguments.of("did_not_know", null, "did_not_know"),
                Arguments.of("declined", null, "declined"),
                Arguments.of(null, "Evidence is insufficient.", "evidence_insufficient"),
                Arguments.of(null, "I do not know who owns this.", "owner_unidentified"));
    }

    @Test
    void namedOwnerCreatesTerminalOwnershipGapWithoutInvitationOrPressure() throws Exception {
        var question = beginQuestion();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/choice")
                        .cookie(new Cookie("findworks_interview", GRANT)).with(csrf())
                        .param("questionId", question.toString()).param("expectedRevision", "2")
                        .param("choice", "other_owner").param("owner", "Payments Platform team"))
                .andExpect(status().is3xxRedirection());
        worker.runNext();

        assertThat(jdbc.sql("""
                SELECT unresolved_subject || ':' || why_current_participant_cannot_answer || ':' || owner_description
                FROM ownership_gap_outcomes
                """).query(String.class).single())
                .isEqualTo("Retry decision rules:Another owner holds this knowledge.:Payments Platform team");
        assertThat(jdbc.sql("SELECT participation_signal FROM evidence").query(String.class).single())
                .isEqualTo("other_owner");
        assertThat(jdbc.sql("SELECT count(*) FROM invitations").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT investigation_item_id FROM interview_questions ORDER BY sequence DESC LIMIT 1")
                .query(UUID.class).single()).isEqualTo(LOW_ITEM);
    }

    @Test
    void clarificationRequestCreatesNoEvidenceAndOneLinkedQuestion() throws Exception {
        var question = beginQuestion();
        var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/clarify")
                .cookie(new Cookie("findworks_interview", GRANT)).with(csrf())
                .param("questionId", question.toString()).param("expectedRevision", "2");
        mvc.perform(request).andExpect(status().is3xxRedirection());
        mvc.perform(request).andExpect(status().is3xxRedirection());
        assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("""
                SELECT trigger || ':' || (source_question_id = ?) FROM interview_runtime_runs
                ORDER BY created_at DESC LIMIT 1
                """).param(question).query(String.class).single()).isEqualTo("clarification_request:true");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(new Cookie("findworks_interview", GRANT)))
                .andExpect(status().isOk()).andExpect(content().string(
                        org.hamcrest.Matchers.containsString("Clarification requested")));

        worker.runNext();
        assertThat(jdbc.sql("""
                SELECT question_kind || ':' || (clarifies_question_id = ?)
                FROM interview_questions ORDER BY sequence DESC LIMIT 1
                """).param(question).query(String.class).single()).isEqualTo("clarification:true");
        assertThat(jdbc.sql("SELECT count(*) FROM investigation_outcomes").query(Integer.class).single()).isZero();
    }

    @Test
    void unsupportedInferenceStaysUnconfirmedAndGetsLinkedParaphrase() throws Exception {
        var question = beginQuestion();
        answer(question, "It might be three failures before Support retries.");
        var evidence = jdbc.sql("SELECT id FROM evidence").query(UUID.class).single();
        worker.runNext();

        assertThat(jdbc.sql("SELECT knowledge_kind || ':' || confirmation_state FROM candidate_knowledge_claims")
                .query(String.class).single()).isEqualTo("assumption:unconfirmed");
        assertThat(jdbc.sql("SELECT status FROM investigation_results").query(String.class).single())
                .isEqualTo("exploring");
        assertThat(jdbc.sql("""
                SELECT question_kind || ':' || paraphrase_reason || ':' || (source_evidence_id = ?)
                FROM interview_questions ORDER BY sequence DESC LIMIT 1
                """).param(evidence).query(String.class).single())
                .isEqualTo("paraphrase_confirmation:inference:true");
    }

    @Test
    void contradictionPreservesBothEvidenceClaimsAndNeverChoosesWinner() throws Exception {
        var first = beginQuestion();
        answer(first, "Usually Support retries after three failures.");
        worker.runNext();
        var second = jdbc.sql("SELECT id FROM interview_questions ORDER BY sequence DESC LIMIT 1")
                .query(UUID.class).single();
        answer(second, "That conflicts with my earlier answer: it is five failures.");
        worker.runNext();

        assertThat(jdbc.sql("SELECT unresolved_explanation FROM conflict_outcomes")
                .query(String.class).single()).isEqualTo("The retry threshold remains inconsistent.");
        assertThat(jdbc.sql("SELECT claim FROM conflict_members ORDER BY position")
                .query(String.class).list()).containsExactly(
                        "Retry after three failures.", "Retry after five failures.");
        assertThat(jdbc.sql("SELECT count(DISTINCT evidence_id) FROM conflict_members")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT status FROM investigation_results WHERE investigation_item_id = ?")
                .param(HIGH_ITEM).query(String.class).single()).isEqualTo("explicit_outcome");
    }

    @Test
    void outOfScopeAnswerRemainsEvidenceButCannotBecomeCandidateOrDriveParaphrase() throws Exception {
        var question = beginQuestion();
        var answer = "Salary bands are confidential and unrelated to payment retries.";
        answer(question, answer);
        worker.runNext();

        assertThat(jdbc.sql("SELECT answer FROM evidence").query(String.class).single()).isEqualTo(answer);
        assertThat(jdbc.sql("SELECT assessment FROM evidence_scope_assessments")
                .query(String.class).single()).isEqualTo("out_of_scope");
        assertThat(jdbc.sql("SELECT count(*) FROM candidate_knowledge_claims")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM investigation_outcomes")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT question_kind FROM interview_questions ORDER BY sequence DESC LIMIT 1")
                .query(String.class).single()).isEqualTo("ordinary");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(new Cookie("findworks_interview", GRANT)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(answer)));
    }

    @Test
    void foreignEvidenceAndIncompleteConflictRollBackAllSemanticEffects() throws Exception {
        var question = beginQuestion();
        answer(question, "A normal answer.");
        var evidence = jdbc.sql("SELECT id FROM evidence").query(UUID.class).single();
        var work = runtime.claimNext();
        var prepared = runtime.prepare(work, "test-runtime");
        var action = new InterviewRuntimeRepository.NextAction("ask_question", HIGH_ITEM,
                "What exception applies?", null,
                new InterviewRuntimeRepository.Progress("Exploring", "Retries", "Ownership"));
        var foreign = new InterviewRuntimeRepository.OutcomeProposal("assumption", HIGH_ITEM,
                List.of(UUID.randomUUID()), null, null, "assumption", "An invented claim",
                null, null, null, null, List.of());
        assertThatThrownBy(() -> complete(work, prepared, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), work.expectedRevision(), List.of(foreign), List.of(), action)))
                .isInstanceOf(IllegalArgumentException.class);
        var incomplete = new InterviewRuntimeRepository.OutcomeProposal("conflict", HIGH_ITEM,
                List.of(), null, "Not enough", null, null, null, null, null, null,
                List.of(new InterviewRuntimeRepository.ConflictMemberProposal("Only one", evidence)));
        assertThatThrownBy(() -> complete(work, prepared, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), work.expectedRevision(), List.of(incomplete), List.of(), action)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM investigation_outcomes")
                .query(Integer.class).single()).isZero();
        assertThat(state()).isEqualTo("active:3:false");
    }

    @Test
    void repositoryRejectsStaleForeignOrOutcomeBearingFirstTurnsWithoutPartialState() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(new Cookie("findworks_interview", GRANT)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        var work = runtime.claimNext();
        var prepared = runtime.prepare(work, "test-runtime");
        var good = submission(work);

        assertThatThrownBy(() -> complete(work, prepared, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), 2, List.of(), good.nextAction())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> complete(work, prepared, new InterviewRuntimeRepository.Submission(
                UUID.randomUUID(), work.sessionId(), 1, List.of(), good.nextAction())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> complete(work, prepared, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), 1,
                List.of(new InterviewRuntimeRepository.OutcomeProposal("unknown", HIGH_ITEM)), good.nextAction())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> complete(work, prepared, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), 1, List.of(), new InterviewRuntimeRepository.NextAction(
                        "ask_question", LOW_ITEM, "Wrong frontier?", null, good.nextAction().progress()))))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM interview_questions").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT revision FROM interview_sessions").query(Integer.class).single()).isEqualTo(1);
        var event = complete(work, prepared, good);
        assertThat(complete(work, prepared, good)).isEqualTo(event);
        assertThat(jdbc.sql("SELECT count(*) FROM interview_questions").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void participantControlsPreservePlaceClarifyAndAppendARevision() throws Exception {
        var cookie = new Cookie("findworks_interview", GRANT);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(cookie).with(csrf())).andExpect(status().is3xxRedirection());
        worker.runNext();
        var firstQuestion = jdbc.sql("SELECT id FROM interview_questions").query(UUID.class).single();

        jdbc.sql("UPDATE interview_sessions SET active_started_at = now() - interval '17 minutes'").update();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "You are nearing the expected time commitment.")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/continue")
                        .cookie(cookie).with(csrf()).param("expectedRevision", "2"))
                .andExpect(status().is3xxRedirection());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/pause")
                        .cookie(cookie).with(csrf()).param("expectedRevision", "3"))
                .andExpect(status().is3xxRedirection());
        assertThat(state()).isEqualTo("paused:4:true");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your place is saved")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Resume interview")));
        assertThatThrownBy(() -> interviews.pause(GRANT, 3)).isInstanceOf(IllegalArgumentException.class);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/resume")
                        .cookie(cookie).with(csrf()).param("expectedRevision", "4"))
                .andExpect(status().is3xxRedirection());
        assertThat(state()).isEqualTo("active:5:true");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_questions").query(Integer.class).single()).isEqualTo(1);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/clarify")
                        .cookie(cookie).with(csrf()).param("questionId", firstQuestion.toString())
                        .param("expectedRevision", "5"))
                .andExpect(status().is3xxRedirection());
        var clarificationWork = runtime.claimNext();
        assertThat(clarificationWork.trigger()).isEqualTo("clarification_request");
        assertThat(clarificationWork.sourceQuestionId()).isEqualTo(firstQuestion);
        var clarificationPrepared = runtime.prepare(clarificationWork, "test-runtime");
        assertThat(clarificationPrepared.context().sourceQuestionId()).isEqualTo(firstQuestion);
        var clarified = complete(clarificationWork, clarificationPrepared, new InterviewRuntimeRepository.Submission(
                clarificationWork.id(), clarificationWork.sessionId(), clarificationWork.expectedRevision(),
                List.of(), new InterviewRuntimeRepository.NextAction("ask_clarification", HIGH_ITEM,
                        "Which retry case should you describe?", "A concrete case is enough.", firstQuestion,
                        null, null,
                        new InterviewRuntimeRepository.Progress("None", "Retry rules", "Ownership"))));
        assertThat(jdbc.sql("SELECT clarifies_question_id FROM interview_questions WHERE id = ?")
                .param(clarified.questionId()).query(UUID.class).single()).isEqualTo(firstQuestion);
        assertThat(state()).isEqualTo("active:7:true");

        interviews.answer(GRANT, clarified.questionId(), 7, "Original exact response");
        var originalEvidence = jdbc.sql("SELECT id FROM evidence").query(UUID.class).single();
        interviews.revise(GRANT, originalEvidence, 8, "Corrected exact response");
        assertThat(jdbc.sql("SELECT source_type || ':' || answer FROM evidence ORDER BY created_at, id")
                .query(String.class).list()).containsExactlyInAnyOrder(
                        "interviewee_answer:Original exact response",
                        "interviewee_answer_revision:Corrected exact response");
        assertThat(jdbc.sql("SELECT revises_evidence_id FROM evidence WHERE source_type = 'interviewee_answer_revision'")
                .query(UUID.class).single()).isEqualTo(originalEvidence);
        assertThatThrownBy(() -> interviews.revise(GRANT, originalEvidence, 9, "Another response"))
                .isInstanceOf(IllegalArgumentException.class);
        var revision = jdbc.sql("SELECT id FROM evidence WHERE source_type = 'interviewee_answer_revision'")
                .query(UUID.class).single();
        assertThatThrownBy(() -> interviews.revise(GRANT, revision, 9, "Corrected exact response"))
                .isInstanceOf(IllegalArgumentException.class);

        interviews.pause(GRANT, 9);
        assertThat(state()).isEqualTo("paused:10:false");
        interviews.resume(GRANT, 10);
        assertThat(state()).isEqualTo("active:11:false");
        var resumeWork = runtime.claimNext();
        assertThat(resumeWork.trigger()).isEqualTo("resume");
        var resumePrepared = runtime.prepare(resumeWork, "test-runtime");
        assertThat(resumePrepared.context().conversation())
                .extracting(InterviewRuntimeRepository.Exchange::answer)
                .contains("Corrected exact response");

        interviews.endEarly(GRANT, 11, true);
        assertThat(state()).isEqualTo("ended_early:12:false");
        assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM interview_runtime_runs WHERE status = 'cancelled'")
                .query(Integer.class).single()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("SELECT outcome FROM interview_runtime_attempts WHERE runtime_run_id = ?")
                .param(resumeWork.id()).query(String.class).single()).isEqualTo("cancelled");
        assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM runtime_credentials WHERE runtime_run_id = ?")
                .param(resumeWork.id()).query(Boolean.class).single()).isTrue();
        assertThatThrownBy(() -> complete(resumeWork, resumePrepared, submission(resumeWork)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> interviews.resume(GRANT, 12)).isInstanceOf(IllegalArgumentException.class);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your responses remain saved")));
    }

    @Test
    void investigatorTerminationRevokesAccessCancelsRuntimeAndReportsRemainingItems() throws Exception {
        interviews.start(GRANT);
        var session = interviews.missionSession(MISSION, "investigator@findworks.local");
        assertThat(session.remainingItems()).containsExactly("Retry decision rules", "Ownership and exceptions");

        interviews.terminate(MISSION, SESSION, 1, "investigator@findworks.local");

        assertThat(state()).isEqualTo("terminated:2:false");
        assertThat(jdbc.sql("SELECT status FROM interview_runtime_runs").query(String.class).single())
                .isEqualTo("cancelled");
        assertThat(jdbc.sql("SELECT revoked_at IS NOT NULL FROM interview_access_grants")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single()).isZero();
        assertThatThrownBy(() -> interviews.interview(GRANT)).isInstanceOf(InterviewAccessDeniedException.class);
        assertThatThrownBy(() -> interviews.terminate(MISSION, SESSION, 2, "investigator@findworks.local"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void processDeathRestartsOnceThenPublishesOneReplayableFailure() throws Exception {
        start();
        var firstLease = runtime.claimNext();
        assertThat(runtime.fail(firstLease, "test-runtime",
                new RuntimeFailure(RuntimeFailure.Kind.PROCESS_DIED, 0))).isNull();
        assertThat(jdbc.sql("SELECT status || ':' || process_restarts FROM interview_runtime_runs")
                .query(String.class).single()).isEqualTo("queued:1");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_application_events").query(Integer.class).single())
                .isZero();

        jdbc.sql("UPDATE interview_runtime_runs SET available_at = now()").update();
        var replacement = runtime.claimNext();
        assertThat(replacement.executionAttempt()).isEqualTo(2);
        assertThatThrownBy(() -> runtime.fail(firstLease, "test-runtime",
                new RuntimeFailure(RuntimeFailure.Kind.PROCESS_DIED, 0)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("lease is stale");
        var failure = runtime.fail(replacement, "test-runtime",
                new RuntimeFailure(RuntimeFailure.Kind.PROCESS_DIED, 0));

        assertThat(failure.type()).isEqualTo("runtime_failed");
        assertThat(runtime.replay(replacement.id())).isEqualTo(failure);
        assertThat(jdbc.sql("SELECT status || ':' || error_code FROM interview_runtime_runs")
                .query(String.class).single()).isEqualTo("failed:process_died");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_application_events WHERE event_type = 'runtime_failed'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM interview_questions").query(Integer.class).single()).isZero();
        assertThat(state()).isEqualTo("active:1:false");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(new Cookie("findworks_interview", GRANT)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "We could not prepare the next question")));
    }

    @Test
    void participantRetriesExhaustedAnswerWorkOnceWithoutRepeatingTheAnswer() throws Exception {
        start();
        var initial = runtime.claimNext();
        var initialPrepared = runtime.prepare(initial, "test-runtime");
        var question = complete(initial, initialPrepared, submission(initial)).questionId();
        interviews.answer(GRANT, question, 2, "One durable answer");
        var evidence = jdbc.sql("SELECT id FROM evidence").query(UUID.class).single();

        var first = runtime.claimNext();
        assertThat(runtime.fail(first, "test-runtime",
                new RuntimeFailure(RuntimeFailure.Kind.TRANSIENT_MODEL, 2))).isNull();
        jdbc.sql("UPDATE interview_runtime_runs SET available_at = now() WHERE id = ?")
                .param(first.id()).update();
        var exhausted = runtime.claimNext();
        assertThat(runtime.fail(exhausted, "test-runtime",
                new RuntimeFailure(RuntimeFailure.Kind.TRANSIENT_MODEL, 1)).type())
                .isEqualTo("runtime_failed");

        var cookie = new Cookie("findworks_interview", GRANT);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Retry")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Pause and leave")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("End interview early")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("One durable answer")));
        for (var attempt = 0; attempt < 2; attempt++) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/retry")
                            .cookie(cookie).with(csrf()).param("expectedRevision", "3"))
                    .andExpect(status().is3xxRedirection());
        }

        assertThat(jdbc.sql("""
                SELECT generation || ':' || status FROM interview_runtime_runs
                WHERE trigger = 'accepted_evidence' ORDER BY generation
                """).query(String.class).list()).containsExactly("1:failed", "2:queued");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM interview_runtime_runs
                WHERE trigger = 'accepted_evidence' AND evidence_id = ? AND expected_revision = 3
                """).param(evidence).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void participantSeesAndRevisesAnyCurrentAnswerInOwnSession() throws Exception {
        start();
        var firstWork = runtime.claimNext();
        var firstQuestion = complete(firstWork, runtime.prepare(firstWork, "test-runtime"),
                submission(firstWork)).questionId();
        interviews.answer(GRANT, firstQuestion, 2, "Earlier answer");
        var earlierEvidence = jdbc.sql("SELECT id FROM evidence WHERE answer = 'Earlier answer'")
                .query(UUID.class).single();

        var secondWork = runtime.claimNext();
        var secondQuestion = complete(secondWork, runtime.prepare(secondWork, "test-runtime"),
                new InterviewRuntimeRepository.Submission(secondWork.id(), secondWork.sessionId(),
                        secondWork.expectedRevision(), List.of(),
                        new InterviewRuntimeRepository.NextAction("ask_question", HIGH_ITEM,
                                "Who owns retry exceptions?", null,
                                new InterviewRuntimeRepository.Progress(
                                        "Retry rules", "Ownership", "Exceptions")))).questionId();
        interviews.answer(GRANT, secondQuestion, 4, "Later answer");

        var cookie = new Cookie("findworks_interview", GRANT);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your previous responses")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Earlier answer")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Later answer")));

        interviews.revise(GRANT, earlierEvidence, 5, "Revised earlier answer");
        assertThat(interviews.interview(GRANT).previousAnswers())
                .extracting(InterviewRepository.PreviousAnswer::answer)
                .containsExactly("Revised earlier answer", "Later answer");
        assertThat(jdbc.sql("SELECT answer FROM evidence WHERE id = ?")
                .param(earlierEvidence).query(String.class).single()).isEqualTo("Earlier answer");
        assertThat(jdbc.sql("""
                SELECT revises_evidence_id FROM evidence
                WHERE answer = 'Revised earlier answer'
                """).query(UUID.class).single()).isEqualTo(earlierEvidence);
        assertThatThrownBy(() -> interviews.revise(GRANT, earlierEvidence, 6, "Third answer"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void recentInterviewActivityDefersInactiveDiscoveryDeletion() {
        jdbc.sql("""
                UPDATE discoveries SET last_activity_at = now() - interval '181 days',
                    retention_due_at = now() - interval '1 day' WHERE id = ?
                """).param(DISCOVERY).update();

        retention.schedule();

        assertThat(jdbc.sql("SELECT status FROM discoveries WHERE id = ?")
                .param(DISCOVERY).query(String.class).single()).isEqualTo("active");
        assertThat(jdbc.sql("SELECT retention_due_at > now() + interval '179 days' FROM discoveries WHERE id = ?")
                .param(DISCOVERY).query(Boolean.class).single()).isTrue();
    }

    @Test
    void expiredLeaseIsReconciledOnceAndSecondExpiryIsExhausted() throws Exception {
        start();
        var abandoned = runtime.claimNext();
        jdbc.sql("UPDATE interview_runtime_runs SET lease_until = now() - interval '1 second'").update();

        var restarted = runtime.claimNext();
        assertThat(restarted.id()).isEqualTo(abandoned.id());
        assertThat(restarted.processRestarts()).isEqualTo(1);
        assertThat(restarted.executionAttempt()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT outcome FROM interview_runtime_attempts
                WHERE execution_attempt = 1
                """).query(String.class).single()).isEqualTo("process_died");

        jdbc.sql("UPDATE interview_runtime_runs SET lease_until = now() - interval '1 second'").update();
        assertThat(runtime.claimNext()).isNull();
        assertThat(jdbc.sql("SELECT status || ':' || attempts FROM interview_runtime_runs")
                .query(String.class).single()).isEqualTo("failed:2");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_application_events WHERE event_type = 'runtime_failed'")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM audit_records WHERE action = 'interview_runtime_failed'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void transientModelRetriesStopAtThreeTotalAttempts() throws Exception {
        start();
        var first = runtime.claimNext();
        assertThat(runtime.fail(first, "test-runtime",
                new RuntimeFailure(RuntimeFailure.Kind.TRANSIENT_MODEL, 2))).isNull();
        jdbc.sql("UPDATE interview_runtime_runs SET available_at = now()").update();
        var second = runtime.claimNext();
        var failure = runtime.fail(second, "test-runtime",
                new RuntimeFailure(RuntimeFailure.Kind.TRANSIENT_MODEL, 1));

        assertThat(failure.type()).isEqualTo("runtime_failed");
        assertThat(jdbc.sql("SELECT status || ':' || model_attempts FROM interview_runtime_runs")
                .query(String.class).single()).isEqualTo("failed:3");
        assertThat(jdbc.sql("SELECT sum(model_attempts) FROM interview_runtime_attempts")
                .query(Integer.class).single()).isEqualTo(3);
        assertThat(runtime.claimNext()).isNull();
    }

    @Test
    void checkpointResumesOnlyForTheExactRuntimeScopeAndCredentialsRotate() throws Exception {
        start();
        var first = runtime.claimNext();
        var checkpoint = "opaque-pi-checkpoint".getBytes(StandardCharsets.UTF_8);
        runtime.fail(first, "test-runtime",
                new RuntimeFailure(RuntimeFailure.Kind.TRANSIENT_MODEL, 1, checkpoint));
        assertThat(jdbc.sql("SELECT ciphertext = ? FROM runtime_checkpoints")
                .param(checkpoint).query(Boolean.class).single()).isFalse();

        jdbc.sql("UPDATE interview_runtime_runs SET available_at = now()").update();
        var replacement = runtime.claimNext();
        var firstPrepared = runtime.prepare(replacement, "test-runtime");
        assertThat(firstPrepared.checkpoint()).isEqualTo(checkpoint);
        var rotated = runtime.prepare(replacement, "test-runtime");
        assertThatThrownBy(() -> complete(replacement, firstPrepared, submission(replacement)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("credential scope");
        assertThat(complete(replacement, rotated, submission(replacement)).type()).isEqualTo("question_ready");
        assertThat(jdbc.sql("SELECT count(*) FROM runtime_credentials WHERE revoked_at IS NOT NULL")
                .query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM runtime_credentials WHERE used_at IS NOT NULL")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void staleRevisionCannotCommitAndAuthoritativeStateWins() throws Exception {
        start();
        var work = runtime.claimNext();
        var prepared = runtime.prepare(work, "test-runtime");
        jdbc.sql("UPDATE interview_sessions SET revision = revision + 1 WHERE id = ?")
                .param(SESSION).update();

        assertThatThrownBy(() -> complete(work, prepared, submission(work)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stale");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_questions").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT revision FROM interview_sessions").query(Integer.class).single()).isEqualTo(2);

        var failure = runtime.fail(work, "test-runtime",
                new RuntimeFailure(RuntimeFailure.Kind.STALE_SCOPE, 0));
        assertThat(failure.type()).isEqualTo("runtime_failed");
        assertThat(jdbc.sql("SELECT error_code FROM interview_runtime_runs").query(String.class).single())
                .isEqualTo("stale_runtime_scope");
    }

    @Test
    void mismatchedOrCorruptCheckpointIsDiscardedAndRebuilt() throws Exception {
        start();
        var first = runtime.claimNext();
        runtime.fail(first, "runtime-v1", new RuntimeFailure(RuntimeFailure.Kind.TRANSIENT_MODEL, 1,
                "checkpoint-v1".getBytes(StandardCharsets.UTF_8)));
        jdbc.sql("UPDATE interview_runtime_runs SET available_at = now()").update();

        var second = runtime.claimNext();
        assertThat(runtime.prepare(second, "runtime-v2").checkpoint()).isNull();
        assertThat(jdbc.sql("SELECT status || ':' || discard_reason FROM runtime_checkpoints")
                .query(String.class).single()).isEqualTo("discarded:runtime_mismatch");
        runtime.fail(second, "runtime-v2", new RuntimeFailure(RuntimeFailure.Kind.TRANSIENT_MODEL, 1,
                "checkpoint-v2".getBytes(StandardCharsets.UTF_8)));
        jdbc.sql("UPDATE interview_runtime_runs SET available_at = now()").update();

        var third = runtime.claimNext();
        jdbc.sql("UPDATE runtime_checkpoints SET ciphertext = set_byte(ciphertext, 0, get_byte(ciphertext, 0) # 1)")
                .update();
        assertThat(runtime.prepare(third, "runtime-v2").checkpoint()).isNull();
        assertThat(jdbc.sql("SELECT status || ':' || discard_reason FROM runtime_checkpoints")
                .query(String.class).single()).isEqualTo("discarded:decrypt_failed");
        assertThat(jdbc.sql("SELECT revision FROM interview_sessions").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM interview_questions").query(Integer.class).single()).isZero();
    }

    @Test
    void concurrentSessionsCannotCrossContextCredentialsEffectsOrEvents() throws Exception {
        seedSecondSession();
        interviews.start(GRANT);
        interviews.start(SECOND_GRANT);
        var first = runtime.claimNext();
        var second = runtime.claimNext();
        var original = first.sessionId().equals(SESSION) ? first : second;
        var other = first.sessionId().equals(SECOND_SESSION) ? first : second;
        var originalPrepared = runtime.prepare(original, "test-runtime");
        var otherPrepared = runtime.prepare(other, "test-runtime");

        assertThat(originalPrepared.context().sharedContext()).containsExactly("SHARED-PAYMENT-CONTEXT");
        assertThat(otherPrepared.context().sharedContext()).containsExactly("SECOND-SESSION-CONTEXT");
        assertThatThrownBy(() -> runtime.complete(original, result(original, HIGH_ITEM,
                "Original question", otherPrepared.credential())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("credential scope");

        runtime.complete(original, result(original, HIGH_ITEM, "Original question", originalPrepared.credential()));
        runtime.complete(other, result(other, SECOND_ITEM, "Second question", otherPrepared.credential()));
        assertThat(jdbc.sql("""
                SELECT count(*) FROM interview_questions q
                WHERE (q.interview_session_id = ? AND q.investigation_item_id = ?)
                   OR (q.interview_session_id = ? AND q.investigation_item_id = ?)
                """).params(SESSION, HIGH_ITEM, SECOND_SESSION, SECOND_ITEM).query(Integer.class).single())
                .isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM interview_application_events e
                JOIN interview_runtime_runs r ON r.id = e.runtime_run_id
                    AND r.interview_session_id = e.interview_session_id
                WHERE e.event_type = 'question_ready'
                """).query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT count(*) FROM runtime_checkpoints c
                JOIN interview_runtime_runs r ON r.id = c.runtime_run_id
                    AND r.interview_session_id = c.interview_session_id
                    AND r.interview_mission_id = c.interview_mission_id
                """).query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void questionCountAndAssumptionCannotBypassRequiredOutcomeEligibility() throws Exception {
        var question = beginQuestion();
        answer(question, "Support might retry after three failures.");
        jdbc.sql("""
                INSERT INTO interview_questions (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    investigation_item_id, sequence, question, question_kind, answered_at
                ) SELECT gen_random_uuid(), organisation_id, discovery_id, id, interview_mission_id,
                         ?, series, 'Already asked ' || series, 'ordinary', now()
                  FROM interview_sessions, generate_series(2, 12) series WHERE id = ?
                """).params(HIGH_ITEM, SESSION).update();
        var work = runtime.claimNext();
        var prepared = runtime.prepare(work, "test-runtime");
        var evidence = jdbc.sql("SELECT id FROM evidence").query(UUID.class).single();
        var assumption = new InterviewRuntimeRepository.OutcomeProposal("assumption", HIGH_ITEM,
                List.of(evidence), null, null, "assumption", "Support may retry after three failures.",
                null, null, null, null, List.of());

        assertThatThrownBy(() -> complete(work, prepared, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), work.expectedRevision(), List.of(assumption), List.of(),
                completionAction("All required work is covered.", List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required Investigation Item");

        assertThat(jdbc.sql("SELECT count(*) FROM interview_completion_proposals")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM interview_application_events WHERE event_type = 'completion_confirmation_ready'")
                .query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM investigation_outcomes")
                .query(Integer.class).single()).isZero();
        assertThat(state()).isEqualTo("active:3:false");
    }

    @Test
    void eligibleProposalIsReplayableAndContinuePreservesWorkUnderRevision() throws Exception {
        var pending = eligibleProposal();
        var evidenceCount = jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single();
        var outcomeCount = jdbc.sql("SELECT count(*) FROM investigation_outcomes").query(Integer.class).single();

        assertThat(pending.event().type()).isEqualTo("completion_confirmation_ready");
        assertThat(runtime.replay(pending.work().id())).isEqualTo(pending.event());
        assertThat(state()).isEqualTo("active:4:false");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_completion_unresolved_refs")
                .query(Integer.class).single()).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(new Cookie("findworks_interview", GRANT)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ready to finish")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Finish interview")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Continue interviewing")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("exception owner remains unknown")));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/pause")
                        .cookie(new Cookie("findworks_interview", GRANT)).with(csrf())
                        .param("expectedRevision", "4"))
                .andExpect(status().isBadRequest());
        assertThat(state()).isEqualTo("active:4:false");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/interview/completion/continue")
                        .cookie(new Cookie("findworks_interview", GRANT)).with(csrf())
                        .param("proposalId", pending.proposalId().toString()).param("expectedRevision", "4"))
                .andExpect(status().is3xxRedirection());
        assertThat(state()).isEqualTo("active:5:false");
        assertThat(jdbc.sql("SELECT status FROM interview_completion_proposals")
                .query(String.class).single()).isEqualTo("continued");
        assertThat(jdbc.sql("SELECT trigger || ':' || expected_revision || ':' || status FROM interview_runtime_runs ORDER BY created_at DESC LIMIT 1")
                .query(String.class).single()).isEqualTo("resume:5:queued");
        assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single()).isEqualTo(evidenceCount);
        assertThat(jdbc.sql("SELECT count(*) FROM investigation_outcomes")
                .query(Integer.class).single()).isEqualTo(outcomeCount);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/interview/completion/finish")
                        .cookie(new Cookie("findworks_interview", GRANT)).with(csrf())
                        .param("proposalId", pending.proposalId().toString()).param("expectedRevision", "4"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM interview_runtime_runs WHERE trigger = 'resume'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void finishRequiresExactCurrentRecapAndQueuesExtractionWithoutReopening() throws Exception {
        var pending = eligibleProposal();
        var evidenceCount = jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single();
        var outcomeCount = jdbc.sql("SELECT count(*) FROM investigation_outcomes").query(Integer.class).single();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/interview/completion/finish")
                        .cookie(new Cookie("findworks_interview", GRANT)).with(csrf())
                        .param("proposalId", UUID.randomUUID().toString()).param("expectedRevision", "4"))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/interview/completion/finish")
                        .cookie(new Cookie("findworks_interview", GRANT)).with(csrf())
                        .param("proposalId", pending.proposalId().toString()).param("expectedRevision", "4"))
                .andExpect(status().is3xxRedirection());

        assertThat(state()).isEqualTo("completed:5:false");
        assertThat(jdbc.sql("SELECT status || ':' || (decided_at IS NOT NULL) FROM interview_completion_proposals")
                .query(String.class).single()).isEqualTo("confirmed:true");
        assertThat(jdbc.sql("SELECT completed_at IS NOT NULL FROM interview_sessions")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM evidence").query(Integer.class).single()).isEqualTo(evidenceCount);
        assertThat(jdbc.sql("SELECT count(*) FROM investigation_outcomes")
                .query(Integer.class).single()).isEqualTo(outcomeCount);
        assertThat(jdbc.sql("SELECT trigger || ':' || status FROM interview_runtime_runs ORDER BY created_at DESC LIMIT 1")
                .query(String.class).single()).isEqualTo("findings_extraction:queued");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(new Cookie("findworks_interview", GRANT)))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("participation is complete")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "Finish interview"))));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/missions/{id}", MISSION)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Required follow-up remains")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ownership and exceptions")));
        assertThat(jdbc.sql("SELECT count(*) FROM audit_records WHERE action = 'interview_session_completed'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void isolatedExtractionCommitsOneImmutableGroupedPackageWithExactProvenance() throws Exception {
        var pending = eligibleProposal();
        interviews.finish(GRANT, pending.proposalId(), 4);

        worker.runNext();
        worker.runNext();

        assertThat(state()).isEqualTo("completed:5:false");
        assertThat(jdbc.sql("SELECT count(*) FROM findings_packages").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM findings_package_versions").query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM findings_package_results").query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("SELECT category || ':' || confirmation_state FROM knowledge_item_versions")
                .query(String.class).single()).isEqualTo("rule:unreviewed");
        assertThat(jdbc.sql("""
                SELECT c.quotation = substring(e.answer FROM c.start_offset + 1 FOR c.end_offset - c.start_offset)
                    AND e.participant_id = ? AND e.source_type = 'interviewee_answer'
                FROM knowledge_item_evidence_citations c
                JOIN evidence e ON e.id = c.evidence_id
                """).param(PARTICIPANT).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM findings_package_unresolved_outcomes")
                .query(Integer.class).single()).isEqualTo(1);
        var packageVersion = jdbc.sql("SELECT id FROM findings_package_versions")
                .query(UUID.class).single();

        worker.runNext();
        assertThat(jdbc.sql("SELECT count(*) FROM findings_package_versions").query(Integer.class).single()).isEqualTo(1);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/missions/{id}/findings/{version}", MISSION, packageVersion)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Support retries after three failures.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Billing manager")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("The exception owner remains unknown.")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("PRIVATE-MISSION-CONTEXT"))));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/missions/{id}/findings/{version}", MISSION, packageVersion)
                        .with(user("outsider@example.com").roles("INVESTIGATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/missions/{id}/findings/{version}", MISSION, packageVersion)
                        .cookie(new Cookie("findworks_interview", GRANT)))
                .andExpect(status().is3xxRedirection())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(
                        "Support retries after three failures."))));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL ROLE findworks_application").update();
            jdbc.sql("SELECT set_config('findworks.organisation_id', ?, true)")
                    .param(ORGANISATION.toString()).query(String.class).single();
            jdbc.sql("UPDATE knowledge_item_versions SET claim = 'changed'").update();
        })).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void invalidProvenanceRollsBackWholePackageAndFailedExtractionRetriesIndependently() throws Exception {
        var pending = eligibleProposal();
        interviews.finish(GRANT, pending.proposalId(), 4);
        var work = runtime.claimNext();
        var prepared = findings.prepare(work, "test-runtime");
        var evidence = prepared.context().evidence().stream()
                .filter(value -> value.investigationItemId().equals(HIGH_ITEM)).findFirst().orElseThrow();
        var item = new FindingsRepository.KnowledgeItemProposal(UUID.randomUUID(), "rule",
                "Support retries after three failures.", List.of(new FindingsRepository.CitationProposal(
                        evidence.id(), 0, 7, "Not the source")), List.of());
        var submission = new FindingsRepository.Submission(work.id(), work.sessionId(), work.expectedRevision(),
                List.of(new FindingsRepository.GroupProposal(HIGH_ITEM, List.of(item), List.of()),
                        new FindingsRepository.GroupProposal(LOW_ITEM, List.of(),
                                prepared.context().outcomes().stream().filter(FindingsRepository.Outcome::unresolved)
                                        .map(FindingsRepository.Outcome::id).toList())));

        assertThatThrownBy(() -> findings.complete(work, new FindingsExtractionRunner.Result(submission,
                "test-runtime", 1, prepared.credential())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quotation");
        assertThat(jdbc.sql("SELECT count(*) FROM findings_packages").query(Integer.class).single()).isZero();
        runtime.fail(work, "test-runtime", new RuntimeFailure(RuntimeFailure.Kind.INVALID_OUTPUT, 0));
        assertThat(state()).isEqualTo("completed:5:false");
        assertThat(findings.view(MISSION, "investigator@findworks.local").status()).isEqualTo("failed");

        findings.retry(MISSION, "investigator@findworks.local");
        assertThat(jdbc.sql("SELECT generation || ':' || status FROM interview_runtime_runs WHERE trigger = 'findings_extraction' ORDER BY generation")
                .query(String.class).list()).containsExactly("1:failed", "2:queued");
        worker.runNext();
        assertThat(jdbc.sql("SELECT version FROM findings_package_versions").query(Integer.class).single()).isEqualTo(1);
        assertThat(state()).isEqualTo("completed:5:false");
    }

    @Test
    void quotationOffsetsUseUnicodeCodePointsAndDisambiguateRepeatedText() throws Exception {
        var pending = eligibleProposal();
        var original = jdbc.sql("SELECT id FROM evidence WHERE investigation_item_id = ? ORDER BY created_at LIMIT 1")
                .param(HIGH_ITEM).query(UUID.class).single();
        var revision = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO evidence (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    participant_id, question_id, investigation_item_id, source_type, answer,
                    revises_evidence_id
                ) SELECT ?, organisation_id, discovery_id, interview_session_id,
                         interview_mission_id, participant_id, question_id, investigation_item_id,
                         'interviewee_answer_revision', '😀 repeat repeat', id
                  FROM evidence WHERE id = ?
                """).params(revision, original).update();
        jdbc.sql("""
                INSERT INTO investigation_outcome_evidence (
                    organisation_id, interview_session_id, interview_mission_id,
                    investigation_item_id, outcome_id, evidence_id
                ) SELECT organisation_id, interview_session_id, interview_mission_id,
                         investigation_item_id, outcome_id, ?
                  FROM investigation_outcome_evidence WHERE evidence_id = ?
                """).params(revision, original).update();
        interviews.finish(GRANT, pending.proposalId(), 4);
        var work = runtime.claimNext();
        var prepared = findings.prepare(work, "test-runtime");
        var current = prepared.context().evidence().stream()
                .filter(value -> value.investigationItemId().equals(HIGH_ITEM) && value.current())
                .findFirst().orElseThrow();
        var knowledge = new FindingsRepository.KnowledgeItemProposal(UUID.randomUUID(), "term",
                "The repeated term is used.", List.of(new FindingsRepository.CitationProposal(
                        current.id(), 9, 15, "repeat")), List.of());
        var groups = prepared.context().investigationItems().stream().map(item ->
                new FindingsRepository.GroupProposal(item.id(), item.id().equals(HIGH_ITEM)
                        ? List.of(knowledge) : List.of(), prepared.context().outcomes().stream()
                        .filter(FindingsRepository.Outcome::unresolved)
                        .filter(outcome -> outcome.investigationItemId().equals(item.id()))
                        .map(FindingsRepository.Outcome::id).toList())).toList();

        findings.complete(work, new FindingsExtractionRunner.Result(new FindingsRepository.Submission(
                work.id(), work.sessionId(), work.expectedRevision(), groups), "test-runtime", 1,
                prepared.credential()));

        assertThat(jdbc.sql("SELECT start_offset || ':' || end_offset || ':' || quotation FROM knowledge_item_evidence_citations")
                .query(String.class).single()).isEqualTo("9:15:repeat");
    }

    @Test
    void investigatorCorrectionAppendsEvidenceAndAnAcceptedKnowledgeVersion() throws Exception {
        var packageVersion = readyFindings();
        var originalVersion = jdbc.sql("SELECT id FROM knowledge_item_versions")
                .query(UUID.class).single();
        var originalEvidence = jdbc.sql("SELECT evidence_id FROM knowledge_item_evidence_citations")
                .query(UUID.class).single();
        var originalClaim = jdbc.sql("SELECT claim FROM knowledge_item_versions WHERE id = ?")
                .param(originalVersion).query(String.class).single();
        var originalAnswer = jdbc.sql("SELECT answer FROM evidence WHERE id = ?")
                .param(originalEvidence).query(String.class).single();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/missions/{mission}/findings/{version}/knowledge/{knowledge}/evidence/{evidence}",
                        MISSION, packageVersion, originalVersion, originalEvidence)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(originalAnswer)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("When a failed card payment occurs")));

        var correction = "Support retries a failed card payment after three attempts.";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/missions/{mission}/findings/{version}/knowledge/{knowledge}/correct",
                        MISSION, packageVersion, originalVersion)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")).with(csrf())
                        .param("expectedRevision", "0").param("correction", correction))
                .andExpect(status().is3xxRedirection());

        assertThat(jdbc.sql("SELECT confirmation_state || ':' || claim FROM knowledge_item_versions WHERE id = ?")
                .param(originalVersion).query(String.class).single()).isEqualTo("corrected:" + originalClaim);
        assertThat(jdbc.sql("SELECT answer FROM evidence WHERE id = ?")
                .param(originalEvidence).query(String.class).single()).isEqualTo(originalAnswer);
        assertThat(jdbc.sql("""
                SELECT v.version || ':' || v.confirmation_state || ':' || v.claim
                FROM knowledge_items k JOIN knowledge_item_versions v
                  ON v.knowledge_item_id = k.id AND v.version = k.current_version
                """).query(String.class).single()).isEqualTo("2:accepted:" + correction);
        assertThat(jdbc.sql("""
                SELECT e.source_type || ':' || (e.participant_id IS NULL) || ':' ||
                       (e.actor_membership_id = ?) || ':' || (e.corrects_evidence_id = ?) || ':' ||
                       (c.start_offset = 0) || ':' || (c.end_offset = char_length(e.answer)) || ':' ||
                       (c.quotation = e.answer)
                FROM evidence e JOIN knowledge_item_evidence_citations c ON c.evidence_id = e.id
                WHERE e.source_type = 'investigator_correction'
                """).params(OWNER, originalEvidence).query(String.class).single())
                .isEqualTo("investigator_correction:true:true:true:true:true:true");
        assertThat(jdbc.sql("SELECT review_revision FROM findings_package_reviews")
                .query(Integer.class).single()).isEqualTo(1);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/missions/{mission}/findings/{version}/knowledge/{knowledge}/review",
                        MISSION, packageVersion, originalVersion)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")).with(csrf())
                        .param("expectedRevision", "0").param("state", "accepted"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM evidence WHERE source_type = 'investigator_correction'")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void requiredReviewGateKeepsUnresolvedOutcomeAndRejectsStaleDecisions() throws Exception {
        var packageVersion = readyFindings();
        var knowledgeVersion = jdbc.sql("SELECT id FROM knowledge_item_versions")
                .query(UUID.class).single();
        var unresolved = jdbc.sql("SELECT outcome_id FROM findings_package_unresolved_outcomes")
                .query(UUID.class).single();
        var decisionPath = "/missions/{mission}/findings/{version}/decision";

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(decisionPath,
                        MISSION, packageVersion)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")).with(csrf())
                        .param("expectedRevision", "0").param("decision", "accepted")
                        .param("notes", "Ready for use."))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM findings_package_decisions")
                .query(Integer.class).single()).isZero();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/missions/{mission}/findings/{version}/knowledge/{knowledge}/review",
                        MISSION, packageVersion, knowledgeVersion)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")).with(csrf())
                        .param("expectedRevision", "0").param("state", "accepted"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/missions/{mission}/findings/{version}/outcomes/{outcome}/acknowledge",
                        MISSION, packageVersion, unresolved)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")).with(csrf())
                        .param("expectedRevision", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/missions/{mission}/findings/{version}/outcomes/{outcome}/acknowledge",
                        MISSION, packageVersion, unresolved)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")).with(csrf())
                        .param("expectedRevision", "1"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(decisionPath,
                        MISSION, packageVersion)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")).with(csrf())
                        .param("expectedRevision", "2").param("decision", "accepted")
                        .param("notes", "Reviewed, with follow-up still required."))
                .andExpect(status().is3xxRedirection());

        assertThat(jdbc.sql("SELECT decision || ':' || review_revision || ':' || notes FROM findings_package_decisions")
                .query(String.class).single())
                .isEqualTo("accepted:2:Reviewed, with follow-up still required.");
        assertThat(jdbc.sql("""
                SELECT d.retention_due_at = p.decided_at + interval '90 days'
                FROM discoveries d, findings_package_decisions p WHERE d.id = ?
                """).param(DISCOVERY).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT kind FROM findings_package_unresolved_outcomes")
                .query(String.class).single()).isEqualTo("unknown");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(decisionPath,
                        MISSION, packageVersion)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")).with(csrf())
                        .param("expectedRevision", "2").param("decision", "rejected")
                        .param("notes", "Stale tab."))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.sql("SELECT count(*) FROM findings_package_decisions")
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void exactPackageCanBeRejectedWithoutAcceptingItsFindings() throws Exception {
        var packageVersion = readyFindings();
        jdbc.sql("UPDATE organisations SET retention_days = 120 WHERE id = ?")
                .param(ORGANISATION).update();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/missions/{mission}/findings/{version}/decision", MISSION, packageVersion)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")).with(csrf())
                        .param("expectedRevision", "0").param("decision", "rejected")
                        .param("notes", "Needs another interview."))
                .andExpect(status().is3xxRedirection());

        assertThat(jdbc.sql("SELECT decision || ':' || notes FROM findings_package_decisions")
                .query(String.class).single()).isEqualTo("rejected:Needs another interview.");
        assertThat(jdbc.sql("SELECT confirmation_state FROM knowledge_item_versions")
                .query(String.class).single()).isEqualTo("unreviewed");
        assertThat(jdbc.sql("""
                SELECT d.retention_due_at = p.decided_at + interval '120 days'
                FROM discoveries d, findings_package_decisions p WHERE d.id = ?
                """).param(DISCOVERY).query(Boolean.class).single()).isTrue();
    }

    @Test
    void optionalUnreviewedFindingDoesNotBlockExactPackageAcceptance() throws Exception {
        var packageVersion = readyFindings();
        jdbc.sql("UPDATE findings_package_results SET required = false "
                + "WHERE findings_package_version_id = ? AND investigation_item_id = ?")
                .params(packageVersion, LOW_ITEM).update();
        var knowledgeVersion = jdbc.sql("SELECT id FROM knowledge_item_versions")
                .query(UUID.class).single();

        findings.reviewKnowledge(MISSION, packageVersion, knowledgeVersion, 0,
                "accepted", "investigator@findworks.local");
        findings.decide(MISSION, packageVersion, 1, "accepted",
                "Required findings reviewed.", "investigator@findworks.local");

        assertThat(jdbc.sql("SELECT decision FROM findings_package_decisions")
                .query(String.class).single()).isEqualTo("accepted");
        assertThat(jdbc.sql("SELECT count(*) FROM findings_package_unresolved_outcomes o "
                + "LEFT JOIN findings_unresolved_outcome_reviews r ON r.outcome_id = o.outcome_id "
                + "WHERE r.id IS NULL").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void discoveryDeletionBlocksImmediatelyThenPurgesTheWholeContentGraph() throws Exception {
        var packageVersion = readyFindings();
        var knowledgeVersion = jdbc.sql("SELECT id FROM knowledge_item_versions")
                .query(UUID.class).single();
        var unresolved = jdbc.sql("SELECT outcome_id FROM findings_package_unresolved_outcomes")
                .query(UUID.class).single();
        findings.reviewKnowledge(MISSION, packageVersion, knowledgeVersion, 0,
                "accepted", "investigator@findworks.local");
        findings.acknowledgeOutcome(MISSION, packageVersion, unresolved, 1,
                "investigator@findworks.local");
        findings.decide(MISSION, packageVersion, 2, "accepted", "Retain for reference.",
                "investigator@findworks.local");
        var unrelated = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO discoveries (id, organisation_id, owner_membership_id, title, objective)
                VALUES (?, ?, ?, 'Other Discovery', 'Must remain')
                """).params(unrelated, ORGANISATION, OWNER).update();

        retention.requestDiscoveryDeletion(DISCOVERY, "investigator@findworks.local");

        assertThat(jdbc.sql("SELECT status || ':' || (access_blocked_at IS NOT NULL) FROM discoveries WHERE id = ?")
                .param(DISCOVERY).query(String.class).single()).isEqualTo("deletion_pending:true");
        assertThat(jdbc.sql("SELECT bool_and(revoked_at IS NOT NULL) FROM interview_access_grants")
                .query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT bool_and(invalidated_at IS NOT NULL) FROM findings_package_versions")
                .query(Boolean.class).single()).isTrue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/discoveries/{id}", DISCOVERY)
                        .with(user("investigator@findworks.local").roles("INVESTIGATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(new Cookie("findworks_interview", GRANT)))
                .andExpect(status().isBadRequest());
        assertThat(runtime.claimNext()).isNull();

        var purge = retention.claimPurge();
        retention.purge(purge);
        retention.purge(purge);

        assertThat(jdbc.sql("SELECT count(*) FROM discoveries WHERE id = ?")
                .param(DISCOVERY).query(Integer.class).single()).isZero();
        for (var table : List.of("discovery_participants", "interview_missions", "investigation_items",
                "invitations", "interview_sessions", "interview_access_grants", "interview_questions",
                "evidence", "investigation_results", "investigation_outcomes", "findings_packages",
                "knowledge_items", "interview_runtime_runs", "runtime_credentials", "runtime_checkpoints",
                "interview_application_events", "discovery_shaping_sessions")) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single())
                    .as(table).isZero();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM discoveries WHERE id = ?")
                .param(unrelated).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("""
                SELECT stage || ':' || (completed_at IS NOT NULL) || ':' ||
                       (backup_expiry_due_at = completed_at + interval '30 days')
                FROM deletion_ledger WHERE target_id = ?
                """).param(DISCOVERY).query(String.class).single()).isEqualTo("completed:true:true");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_records WHERE resource_id = ?")
                .param(DISCOVERY).query(Integer.class).single()).isGreaterThan(0);
    }

    @Test
    void sessionDeletionRemovesOnlyItsSubtreeAndUnreferencedParticipant() throws Exception {
        readyFindings();
        seedSecondSession();
        var missionItemCount = jdbc.sql("SELECT count(*) FROM investigation_items WHERE interview_mission_id = ?")
                .param(MISSION).query(Integer.class).single();

        retention.requestSessionDeletion(MISSION, SESSION, "investigator@findworks.local");
        assertThat(jdbc.sql("SELECT access_blocked_at IS NOT NULL FROM interview_sessions WHERE id = ?")
                .param(SESSION).query(Boolean.class).single()).isTrue();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/interview")
                        .cookie(new Cookie("findworks_interview", GRANT)))
                .andExpect(status().isBadRequest());
        var purge = retention.claimPurge();
        retention.purge(purge);

        assertThat(jdbc.sql("SELECT count(*) FROM interview_sessions WHERE id = ?")
                .param(SESSION).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM evidence WHERE interview_session_id = ?")
                .param(SESSION).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM findings_packages WHERE interview_session_id = ?")
                .param(SESSION).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM interview_runtime_runs WHERE interview_session_id = ?")
                .param(SESSION).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM investigation_results WHERE interview_session_id = ?")
                .param(SESSION).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM investigation_items WHERE interview_mission_id = ?")
                .param(MISSION).query(Integer.class).single()).isEqualTo(missionItemCount);
        assertThat(jdbc.sql("SELECT count(*) FROM interview_sessions WHERE id = ?")
                .param(SECOND_SESSION).query(Integer.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM discovery_participants WHERE id = ?")
                .param(PARTICIPANT).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM discovery_participants WHERE id = ?")
                .param(SECOND_PARTICIPANT).query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void isolatedFakePiCanCommitTheCompletionSemanticAction() throws Exception {
        var question = beginQuestion();
        answer(question, "Support retries after three failures.");
        var runId = jdbc.sql("SELECT id FROM interview_runtime_runs WHERE status = 'queued'")
                .query(UUID.class).single();
        seedEligibleOutcomes(runId);

        var work = runtime.claimNext();
        var prepared = runtime.prepare(work, interviewRunner.runtimeVersion());
        var result = interviewRunner.run(new InterviewTurnRunner.Request(prepared.context(),
                prepared.checkpoint(), prepared.credential(), prepared.credentialExpiresAt()));
        runtime.complete(work, result);

        assertThat(jdbc.sql("SELECT status || ':' || coalesce(error_code, 'none') FROM interview_runtime_runs WHERE id = ?")
                .param(runId).query(String.class).single()).isEqualTo("committed:none");
        assertThat(state()).isEqualTo("active:4:false");
        assertThat(jdbc.sql("SELECT event_type FROM interview_application_events ORDER BY created_at DESC LIMIT 1")
                .query(String.class).single()).isEqualTo("completion_confirmation_ready");
        assertThat(jdbc.sql("SELECT recap FROM interview_completion_proposals")
                .query(String.class).single()).contains("exception owner remains unknown");
        assertThat(Files.readString(CAPTURE)).contains("completionCriteria", "expectedCommitment")
                .doesNotContain("PRIVATE-MISSION-CONTEXT", GRANT, "fake-provider-credential");
    }

    private PendingCompletion eligibleProposal() throws Exception {
        var question = beginQuestion();
        answer(question, "Support retries after three failures.");
        var work = runtime.claimNext();
        var prepared = runtime.prepare(work, "test-runtime");
        seedEligibleOutcomes(work.id());

        assertThatThrownBy(() -> complete(work, prepared, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), work.expectedRevision(), List.of(), List.of(),
                completionAction("The exception owner remains unknown.", List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("every unresolved outcome");
        var action = completionAction("The exception owner remains unknown.", List.of(
                new InterviewRuntimeRepository.UnresolvedReference(LOW_ITEM, "unknown")));
        var event = complete(work, prepared, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), work.expectedRevision(), List.of(), List.of(), action));
        assertThat(complete(work, prepared, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), work.expectedRevision(), List.of(), List.of(), action)))
                .isEqualTo(event);
        return new PendingCompletion(work, event, event.completionProposalId());
    }

    private UUID readyFindings() throws Exception {
        var pending = eligibleProposal();
        interviews.finish(GRANT, pending.proposalId(), 4);
        worker.runNext();
        return jdbc.sql("SELECT id FROM findings_package_versions").query(UUID.class).single();
    }

    private void seedEligibleOutcomes(UUID runtimeRunId) {
        var evidence = jdbc.sql("SELECT id FROM evidence").query(UUID.class).single();
        var supported = UUID.randomUUID();
        jdbc.sql("UPDATE investigation_results SET status = 'explicit_outcome' WHERE investigation_item_id = ?")
                .param(HIGH_ITEM).update();
        jdbc.sql("""
                INSERT INTO investigation_outcomes (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    investigation_item_id, investigation_result_id, runtime_run_id, kind
                ) SELECT ?, ?, ?, ?, ?, ?, id, ?, 'supported_knowledge'
                  FROM investigation_results WHERE interview_session_id = ? AND investigation_item_id = ?
                """).params(supported, ORGANISATION, DISCOVERY, SESSION, MISSION, HIGH_ITEM,
                runtimeRunId, SESSION, HIGH_ITEM).update();
        jdbc.sql("""
                INSERT INTO investigation_outcome_evidence (
                    organisation_id, interview_session_id, interview_mission_id,
                    investigation_item_id, outcome_id, evidence_id
                ) VALUES (?, ?, ?, ?, ?, ?)
                """).params(ORGANISATION, SESSION, MISSION, HIGH_ITEM, supported, evidence).update();
        jdbc.sql("""
                INSERT INTO candidate_knowledge_claims (
                    outcome_id, organisation_id, knowledge_kind, claim, confirmation_state
                ) VALUES (?, ?, 'rule', 'Support retries after three failures.', 'confirmed')
                """).params(supported, ORGANISATION).update();

        var lowQuestion = UUID.randomUUID();
        var lowEvidence = UUID.randomUUID();
        var lowResult = UUID.randomUUID();
        var unknown = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO interview_questions (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    investigation_item_id, sequence, question, question_kind, answered_at
                ) VALUES (?, ?, ?, ?, ?, ?, 2, 'Who owns payment exceptions?', 'ordinary', now())
                """).params(lowQuestion, ORGANISATION, DISCOVERY, SESSION, MISSION, LOW_ITEM).update();
        jdbc.sql("""
                INSERT INTO evidence (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    participant_id, question_id, investigation_item_id, source_type, answer, participation_signal
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'interviewee_answer', 'I do not know.', 'did_not_know')
                """).params(lowEvidence, ORGANISATION, DISCOVERY, SESSION, MISSION,
                PARTICIPANT, lowQuestion, LOW_ITEM).update();
        jdbc.sql("""
                INSERT INTO investigation_results (
                    id, organisation_id, discovery_id, interview_session_id,
                    interview_mission_id, investigation_item_id, status
                ) VALUES (?, ?, ?, ?, ?, ?, 'explicit_outcome')
                """).params(lowResult, ORGANISATION, DISCOVERY, SESSION, MISSION, LOW_ITEM).update();
        jdbc.sql("""
                INSERT INTO investigation_outcomes (
                    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
                    investigation_item_id, investigation_result_id, runtime_run_id, kind
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'unknown')
                """).params(unknown, ORGANISATION, DISCOVERY, SESSION, MISSION,
                LOW_ITEM, lowResult, runtimeRunId).update();
        jdbc.sql("""
                INSERT INTO investigation_outcome_evidence (
                    organisation_id, interview_session_id, interview_mission_id,
                    investigation_item_id, outcome_id, evidence_id
                ) VALUES (?, ?, ?, ?, ?, ?)
                """).params(ORGANISATION, SESSION, MISSION, LOW_ITEM, unknown, lowEvidence).update();
        jdbc.sql("""
                INSERT INTO unknown_outcomes (outcome_id, organisation_id, reason, explanation)
                VALUES (?, ?, 'did_not_know', 'The exception owner remains unknown.')
                """).params(unknown, ORGANISATION).update();

    }

    private static InterviewRuntimeRepository.NextAction completionAction(String recap,
            List<InterviewRuntimeRepository.UnresolvedReference> unresolved) {
        return new InterviewRuntimeRepository.NextAction("propose_completion", null, null, null,
                null, null, null, null, recap, unresolved);
    }

    private record PendingCompletion(InterviewRuntimeRepository.Work work,
            InterviewRuntimeRepository.Event event, UUID proposalId) {}

    private void start() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(new Cookie("findworks_interview", GRANT)).with(csrf()))
                .andExpect(status().is3xxRedirection());
    }

    private InterviewRuntimeRepository.Submission submission(InterviewRuntimeRepository.Work work) {
        return new InterviewRuntimeRepository.Submission(work.id(), work.sessionId(), work.expectedRevision(),
                List.of(), new InterviewRuntimeRepository.NextAction("ask_question", HIGH_ITEM,
                        "How do retry rules work?", null,
                        new InterviewRuntimeRepository.Progress("None", "Retry rules", "Ownership")));
    }

    private UUID beginQuestion() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/start")
                        .cookie(new Cookie("findworks_interview", GRANT)).with(csrf()))
                .andExpect(status().is3xxRedirection());
        worker.runNext();
        return jdbc.sql("SELECT id FROM interview_questions ORDER BY sequence DESC LIMIT 1")
                .query(UUID.class).single();
    }

    private void answer(UUID question, String answer) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/interview/answer")
                        .cookie(new Cookie("findworks_interview", GRANT)).with(csrf())
                        .param("questionId", question.toString())
                        .param("expectedRevision", jdbc.sql("SELECT revision FROM interview_sessions")
                                .query(Integer.class).single().toString())
                        .param("answer", answer))
                .andExpect(status().is3xxRedirection());
    }

    private InterviewRuntimeRepository.Event complete(InterviewRuntimeRepository.Work work,
            InterviewRuntimeRepository.Prepared prepared, InterviewRuntimeRepository.Submission submission) {
        return runtime.complete(work, new InterviewTurnRunner.Result(
                submission, "checkpoint".getBytes(StandardCharsets.UTF_8), "test-runtime", 1,
                prepared.credential()));
    }

    private InterviewTurnRunner.Result result(InterviewRuntimeRepository.Work work, UUID item,
            String question, String credential) {
        return new InterviewTurnRunner.Result(new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), work.expectedRevision(), List.of(),
                new InterviewRuntimeRepository.NextAction("ask_question", item, question, null,
                        new InterviewRuntimeRepository.Progress("None", question, "Remaining"))),
                ("checkpoint:" + work.id()).getBytes(StandardCharsets.UTF_8), "test-runtime", 1, credential);
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
                         (gen_random_uuid(), ?, ?, ?, 1, 'unknown'),
                         (gen_random_uuid(), ?, ?, ?, 2, 'conflict'),
                         (gen_random_uuid(), ?, ?, ?, 3, 'ownership_gap'),
                         (gen_random_uuid(), ?, ?, ?, 0, 'supported_knowledge'),
                         (gen_random_uuid(), ?, ?, ?, 1, 'unknown'),
                         (gen_random_uuid(), ?, ?, ?, 2, 'conflict'),
                         (gen_random_uuid(), ?, ?, ?, 3, 'ownership_gap')
                """).params(ORGANISATION, MISSION, HIGH_ITEM,
                ORGANISATION, MISSION, HIGH_ITEM,
                ORGANISATION, MISSION, HIGH_ITEM,
                ORGANISATION, MISSION, HIGH_ITEM,
                ORGANISATION, MISSION, LOW_ITEM,
                ORGANISATION, MISSION, LOW_ITEM,
                ORGANISATION, MISSION, LOW_ITEM,
                ORGANISATION, MISSION, LOW_ITEM).update();
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

    private void seedSecondSession() {
        jdbc.sql("""
                INSERT INTO interview_missions (
                    id, organisation_id, discovery_id, lineage_id, version, status,
                    interviewee_name, interviewee_email, objective, desired_outcome,
                    interviewee_relevance, completion_criteria, expected_commitment,
                    data_use_summary, approved_at
                ) VALUES (?, ?, ?, ?, 1, 'approved', 'Second manager', NULL,
                          'Understand second choices', 'Document second rules', 'Owns second decisions',
                          'Second rules are explicit', '20 minutes', 'Use for this Discovery', now())
                """).params(SECOND_MISSION, ORGANISATION, DISCOVERY, SECOND_MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_contexts
                    (id, organisation_id, interview_mission_id, position, visibility, content)
                VALUES (gen_random_uuid(), ?, ?, 0, 'shared', 'SECOND-SESSION-CONTEXT')
                """).params(ORGANISATION, SECOND_MISSION).update();
        jdbc.sql("""
                INSERT INTO investigation_items (
                    id, organisation_id, interview_mission_id, position, knowledge_gap,
                    opening_question, required, importance, priority, relevant_context
                ) VALUES (?, ?, ?, 0, 'Second rules', NULL, true, 'Isolation', 'high', 'Second context')
                """).params(SECOND_ITEM, ORGANISATION, SECOND_MISSION).update();
        jdbc.sql("""
                INSERT INTO mission_allowed_outcomes (
                    id, organisation_id, interview_mission_id, investigation_item_id, position, outcome_kind
                ) VALUES (gen_random_uuid(), ?, ?, ?, 0, 'supported_knowledge')
                """).params(ORGANISATION, SECOND_MISSION, SECOND_ITEM).update();
        jdbc.sql("""
                INSERT INTO discovery_participants (id, organisation_id, discovery_id, intended_name, email)
                VALUES (?, ?, ?, 'Second manager', 'second-participant@example.com')
                """).params(SECOND_PARTICIPANT, ORGANISATION, DISCOVERY).update();
        jdbc.sql("""
                INSERT INTO interview_sessions (
                    id, organisation_id, discovery_id, interview_mission_id, participant_id
                ) VALUES (?, ?, ?, ?, ?)
                """).params(SECOND_SESSION, ORGANISATION, DISCOVERY, SECOND_MISSION, SECOND_PARTICIPANT).update();
        jdbc.sql("""
                INSERT INTO interview_access_grants (
                    id, organisation_id, interview_session_id, participant_id, token_hash, expires_at
                ) VALUES (gen_random_uuid(), ?, ?, ?, ?, now() + interval '7 days')
                """).params(ORGANISATION, SECOND_SESSION, SECOND_PARTICIPANT, hash(SECOND_GRANT)).update();
    }

    private static Path fakePi() {
        var script = temporary("findworks-fake-interview-pi-", ".py");
        try {
            Files.writeString(script, """
                    #!/usr/bin/env python3
                    import base64, json, os, sys
                    if len(sys.argv) > 1 and sys.argv[1] == "info":
                        print("true")
                        raise SystemExit(0)
                    if len(sys.argv) > 1 and sys.argv[1] == "rm":
                        raise SystemExit(0)
                    request = json.loads(sys.stdin.readline())
                    projection = request["context"]
                    with open(%s, "a", encoding="utf-8") as capture:
                        capture.write(json.dumps({"projection": projection, "argv": sys.argv,
                            "environment": dict(os.environ)}) + "\\n")
                    if request.get("jobKind") == "findings_extraction":
                        evidence = next(value for value in projection["evidence"]
                            if value["investigationItemId"] == %s and value["current"] and value["scope"] == "in_scope")
                        groups = []
                        for item in projection["investigationItems"]:
                            knowledge = []
                            if item["id"] == %s:
                                knowledge = [{
                                    "id": "a0000000-0000-0000-0000-000000000029",
                                    "category": "rule",
                                    "claim": "Support retries after three failures.",
                                    "citations": [{"evidenceId": evidence["id"], "startOffset": 0,
                                        "endOffset": len(evidence["content"]), "quotation": evidence["content"]}],
                                    "links": []
                                }]
                            groups.append({"investigationItemId": item["id"], "knowledgeItems": knowledge,
                                "unresolvedOutcomeIds": [value["id"] for value in projection["outcomes"]
                                    if value["investigationItemId"] == item["id"] and value["unresolved"]]})
                        print(json.dumps({"status": "submitted", "submission": {
                            "runId": projection["runId"], "sessionId": projection["sessionId"],
                            "expectedRevision": projection["expectedRevision"], "groups": groups},
                            "checkpoint": None, "modelAttempts": 1,
                            "findWorksCredential": request["findWorksCredential"]}))
                        raise SystemExit(0)
                    trigger = projection["trigger"]
                    follow_up = trigger == "accepted_evidence"
                    latest = projection["conversation"][-1] if projection["conversation"] else {}
                    answer = latest.get("answer") or ""
                    signal = latest.get("participationSignal")
                    evidence_id = projection.get("acceptedEvidenceId")
                    outcomes = []
                    assessments = []
                    action_kind = "ask_question"
                    target = %s
                    question = ("You mentioned the North Star rule. How do the three failed attempts change escalation?"
                        if follow_up else
                        "When a failed card payment occurs, how do you decide whether Support should retry it?")
                    source_question_id = None
                    source_evidence_id = None
                    paraphrase_reason = None
                    if trigger == "clarification_request":
                        action_kind = "ask_clarification"
                        source_question_id = projection["sourceQuestionId"]
                        question = "Put another way, what happens first when a card payment fails?"
                    elif signal in ["did_not_know", "declined"] or answer in ["Evidence is insufficient.", "I do not know who owns this."]:
                        reason = signal or ("evidence_insufficient" if answer == "Evidence is insufficient." else "owner_unidentified")
                        outcomes = [{"kind": "unknown", "investigationItemId": %s,
                            "evidenceIds": [evidence_id], "reason": reason,
                            "explanation": "The interviewee could not establish the retry rule."}]
                        target = %s
                        question = "Who handles exceptions when the usual payment retry path does not apply?"
                    elif signal == "other_owner":
                        outcomes = [{"kind": "ownership_gap", "investigationItemId": %s,
                            "evidenceIds": [evidence_id], "unresolvedSubject": "Retry decision rules",
                            "whyCurrentParticipantCannotAnswer": "Another owner holds this knowledge.",
                            "ownerDescription": answer.split(":", 1)[1].strip()}]
                        target = %s
                        question = "Without naming anyone else, what exceptions do you handle yourself?"
                    elif "might" in answer.lower():
                        outcomes = [{"kind": "assumption", "investigationItemId": %s,
                            "evidenceIds": [evidence_id], "knowledgeKind": "assumption",
                            "claim": "Support might retry after three failures."}]
                        action_kind = "ask_paraphrase_confirmation"
                        source_evidence_id = evidence_id
                        paraphrase_reason = "inference"
                        question = "Have I understood correctly that three failures may trigger a Support retry?"
                    elif "conflicts" in answer.lower():
                        evidence = [exchange for exchange in projection["conversation"] if exchange.get("evidenceId")]
                        outcomes = [{"kind": "conflict", "investigationItemId": %s, "evidenceIds": [],
                            "explanation": "The retry threshold remains inconsistent.",
                            "conflictMembers": [
                                {"claim": "Retry after three failures.", "evidenceId": evidence[-2]["evidenceId"]},
                                {"claim": "Retry after five failures.", "evidenceId": evidence[-1]["evidenceId"]}
                            ]}]
                        target = %s
                        question = "Who decides how payment exceptions are handled?"
                    elif "salary" in answer.lower():
                        assessments = [{"evidenceId": evidence_id, "assessment": "out_of_scope",
                            "missionBoundaryId": projection["boundaries"][1]["id"],
                            "rationale": "Salary information is prohibited by the Mission."}]
                        question = "Returning to failed payments, what retry signal does Support use?"
                    submission = {
                        "runId": projection["runId"],
                        "sessionId": projection["sessionId"],
                        "expectedRevision": projection["expectedRevision"],
                        "outcomes": outcomes,
                        "scopeAssessments": assessments,
                        "nextAction": {
                            "kind": action_kind,
                            "targetInvestigationItemId": target,
                            "question": question,
                            "humanContext": "Think about the most recent case.",
                            "progress": {
                                "covered": "Retry decisions explored" if follow_up else "No areas covered yet",
                                "current": "Retry decision rules",
                                "remaining": "Ownership and exceptions"
                            }
                        }
                    }
                    required = [item for item in projection["investigationItems"] if item["required"]]
                    if trigger in ["accepted_evidence", "resume"] and required and all(
                            item["resultStatus"] == "explicit_outcome" for item in required):
                        unresolved = [
                            {"investigationItemId": item["id"], "kind": outcome["kind"]}
                            for item in required for outcome in item["outcomes"]
                            if outcome["kind"] in ["unknown", "conflict", "ownership_gap"]
                        ]
                        submission["outcomes"] = []
                        submission["scopeAssessments"] = []
                        submission["nextAction"] = {
                            "kind": "propose_completion",
                            "completionRecap": "The exception owner remains unknown.",
                            "unresolvedReferences": unresolved,
                        }
                    if source_question_id:
                        submission["nextAction"]["sourceQuestionId"] = source_question_id
                    if source_evidence_id:
                        submission["nextAction"]["sourceEvidenceId"] = source_evidence_id
                        submission["nextAction"]["paraphraseReason"] = paraphrase_reason
                    checkpoint = base64.b64encode(("checkpoint:" + projection["runId"]).encode()).decode()
                    print(json.dumps({"status": "submitted", "submission": submission,
                        "checkpoint": checkpoint, "modelAttempts": 1,
                        "findWorksCredential": request["findWorksCredential"]}))
                    """.formatted(pythonString(CAPTURE.toString()),
                    pythonString(HIGH_ITEM.toString()), pythonString(HIGH_ITEM.toString()),
                    pythonString(HIGH_ITEM.toString()), pythonString(HIGH_ITEM.toString()),
                    pythonString(LOW_ITEM.toString()), pythonString(HIGH_ITEM.toString()),
                    pythonString(LOW_ITEM.toString()), pythonString(HIGH_ITEM.toString()),
                    pythonString(HIGH_ITEM.toString()), pythonString(LOW_ITEM.toString())));
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
