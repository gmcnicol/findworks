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
    @Autowired InterviewRepository interviews;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.sql("DELETE FROM discoveries").update();
        jdbc.sql("DELETE FROM audit_records").update();
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
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(exactAnswer))));

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
                .doesNotContain("PRIVATE-MISSION-CONTEXT", "participant-secret@example.com", GRANT);
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
        runtime.fail(failedWork);
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
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(answer))));
    }

    @Test
    void foreignEvidenceAndIncompleteConflictRollBackAllSemanticEffects() throws Exception {
        var question = beginQuestion();
        answer(question, "A normal answer.");
        var evidence = jdbc.sql("SELECT id FROM evidence").query(UUID.class).single();
        var work = runtime.claimNext();
        var action = new InterviewRuntimeRepository.NextAction("ask_question", HIGH_ITEM,
                "What exception applies?", null,
                new InterviewRuntimeRepository.Progress("Exploring", "Retries", "Ownership"));
        var foreign = new InterviewRuntimeRepository.OutcomeProposal("assumption", HIGH_ITEM,
                List.of(UUID.randomUUID()), null, null, "assumption", "An invented claim",
                null, null, null, null, List.of());
        assertThatThrownBy(() -> runtime.complete(work, new InterviewRuntimeRepository.Submission(
                work.id(), work.sessionId(), work.expectedRevision(), List.of(foreign), List.of(), action)))
                .isInstanceOf(IllegalArgumentException.class);
        var incomplete = new InterviewRuntimeRepository.OutcomeProposal("conflict", HIGH_ITEM,
                List.of(), null, "Not enough", null, null, null, null, null, null,
                List.of(new InterviewRuntimeRepository.ConflictMemberProposal("Only one", evidence)));
        assertThatThrownBy(() -> runtime.complete(work, new InterviewRuntimeRepository.Submission(
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
                    if source_question_id:
                        submission["nextAction"]["sourceQuestionId"] = source_question_id
                    if source_evidence_id:
                        submission["nextAction"]["sourceEvidenceId"] = source_evidence_id
                        submission["nextAction"]["paraphraseReason"] = paraphrase_reason
                    print(json.dumps({"id": request["id"], "type": "response", "command": "prompt", "success": True}))
                    print(json.dumps({"type": "tool_execution_end", "toolCallId": "tool-1",
                        "toolName": "submit_interview_turn", "result": {"content": [],
                        "details": {"submission": submission}}, "isError": False}))
                    print(json.dumps({"type": "agent_settled"}))
                    """.formatted(pythonString(CAPTURE.toString()),
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
