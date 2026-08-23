package com.findworks.shaping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "findworks.shaping.worker-cron=-")
@AutoConfigureMockMvc
@Testcontainers
class ShapingFlowTest {

    private static final String EMAIL = "investigator@findworks.local";
    private static final Path FAKE_PI = fakePi();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void pi(DynamicPropertyRegistry properties) {
        properties.add("findworks.pi.executable", FAKE_PI::toString);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ShapingWorker worker;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ShapingRepository repository;

    @BeforeEach
    void clean() {
        jdbc.sql("DELETE FROM discoveries").update();
        jdbc.sql("DELETE FROM audit_records").update();
    }

    @Test
    void investigatorReceivesAndResumesAnAnswerDependentFollowUp() throws Exception {
        var discoveryId = createDiscovery();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/discoveries/{id}/shaping", discoveryId)
                        .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf())
                        .param("content", "Support manually retries failed card payments."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/discoveries/" + discoveryId));
        worker.runNext();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/discoveries/{id}", discoveryId)
                        .with(user(EMAIL).roles("INVESTIGATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Who in Support decides whether to retry a failed card payment?")));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/discoveries/{id}/shaping", discoveryId)
                        .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf())
                        .param("content", "The billing manager decides after checking the account history."))
                .andExpect(status().is3xxRedirection());
        worker.runNext();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/discoveries/{id}", discoveryId)
                        .with(user(EMAIL).roles("INVESTIGATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "What in the account history guides the billing manager?")));

        var messages = jdbc.sql("""
                SELECT author_kind, content, created_at FROM discovery_shaping_messages
                ORDER BY position
                """).query((rs, row) -> new Message(rs.getString("author_kind"), rs.getString("content"),
                        rs.getTimestamp("created_at").toInstant())).list();
        assertThat(messages.stream().map(message -> message.author() + ":" + message.content()))
                .containsExactly(
                        "investigator:Support manually retries failed card payments.",
                        "agent:Who in Support decides whether to retry a failed card payment?",
                        "investigator:The billing manager decides after checking the account history.",
                        "agent:What in the account history guides the billing manager?");
        assertThat(messages).allMatch(message -> message.createdAt() != null);
        assertThat(jdbc.sql("SELECT count(*) FROM shaping_runtime_work WHERE status = 'succeeded'")
                .query(Integer.class).single()).isEqualTo(2);
        assertThat(jdbc.sql("""
                SELECT action FROM audit_records
                WHERE resource_kind = 'discovery_shaping_session' ORDER BY created_at
                """).query(String.class).list())
                .containsExactly("shaping_message_submitted", "shaping_follow_up_ready",
                        "shaping_message_submitted", "shaping_follow_up_ready");
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL ROLE findworks_application").update();
            jdbc.sql("SELECT set_config('findworks.organisation_id', '10000000-0000-0000-0000-000000000001', true)")
                    .query(String.class).single();
            jdbc.sql("UPDATE discovery_shaping_messages SET content = 'changed'").update();
        })).hasRootCauseInstanceOf(java.sql.SQLException.class);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/discoveries/{id}", discoveryId)
                        .with(user("other@example.com").roles("INVESTIGATOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void vagueInputBecomesACompleteProvenanceBackedProposal() throws Exception {
        var discoveryId = createDiscovery();

        submit(discoveryId, "Support handles payment failures.");
        worker.runNext();
        submit(discoveryId, "The billing manager owns retry decisions after checking account history.");
        worker.runNext();
        submit(discoveryId, """
                Complete proposal: interview the billing manager for 20 minutes to document supported retry rules,
                unknowns, conflicts, and ownership gaps. Share payment status and account history, but keep suspected
                staff performance concerns private. Stay within failed card-payment handling and do not discuss salaries.
                A retry means another manual payment attempt. Begin by asking how account history changes the decision.
                Finish when retry rules and exceptions are explicit. Use answers only for this Discovery. The unresolved
                ambiguity is who owns retries when the billing manager is away; represent it as a required high-priority
                Investigation Item alongside the required retry-rule item.
                """);
        worker.runNext();
        worker.runNext();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/discoveries/{id}", discoveryId)
                        .with(user(EMAIL).roles("INVESTIGATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Awaiting confirmation")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Private to Investigator")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("staff performance concerns")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("who owns retries")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Nothing here is authoritative yet")));

        assertThat(jdbc.sql("SELECT status FROM interview_mission_proposals").query(String.class).single())
                .isEqualTo("awaiting_confirmation");
        assertThat(jdbc.sql("""
                SELECT visibility || ':' || content FROM mission_proposal_contexts ORDER BY position
                """).query(String.class).list()).containsExactly(
                        "shared:Payment status and account history may be shared with the interviewee.",
                        "private:Suspected staff performance concerns stay private to the Investigator.");
        assertThat(jdbc.sql("""
                SELECT knowledge_gap, importance, priority, relevant_context, required
                FROM mission_proposal_investigation_items ORDER BY position
                """).query((rs, row) -> List.of(
                        rs.getString("knowledge_gap"), rs.getString("importance"), rs.getString("priority"),
                        rs.getString("relevant_context"), Boolean.toString(rs.getBoolean("required")))).list())
                .containsExactly(
                        List.of("When should Support retry a failed card payment?", "Avoid inconsistent retries.",
                                "high", "The billing manager checks account history.", "true"),
                        List.of("Who owns retries when the billing manager is away?", "Keep decisions accountable.",
                                "high", "No deputy is yet identified.", "true"));
        assertThat(jdbc.sql("""
                SELECT outcome_kind FROM mission_proposal_allowed_outcomes ORDER BY investigation_item_id, position
                """).query(String.class).list()).contains(
                        "supported_knowledge", "unknown", "conflict", "ownership_gap");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM mission_proposal_provenance p
                LEFT JOIN discovery_shaping_messages m ON m.id = p.source_message_id
                LEFT JOIN shaping_runtime_work w ON w.id = p.source_runtime_work_id
                WHERE (p.source_kind = 'investigator_message'
                       AND m.shaping_session_id = p.shaping_session_id AND m.author_kind = 'investigator')
                   OR (p.source_kind = 'agent_proposal'
                       AND w.id = (SELECT runtime_work_id FROM interview_mission_proposals WHERE id = p.proposal_id))
                """).query(Integer.class).single()).isEqualTo(
                        jdbc.sql("SELECT count(*) FROM mission_proposal_provenance").query(Integer.class).single());
        assertThat(jdbc.sql("""
                SELECT content FROM mission_proposal_contexts WHERE visibility = 'shared'
                """).query(String.class).list()).noneMatch(value -> value.contains("staff performance"));
        assertThat(jdbc.sql("SELECT count(*) FROM interview_missions").query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM interview_mission_proposals").query(Integer.class).single())
                .isEqualTo(1);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL ROLE findworks_application").update();
            jdbc.sql("SELECT set_config('findworks.organisation_id', '10000000-0000-0000-0000-000000000001', true)")
                    .query(String.class).single();
            jdbc.sql("UPDATE interview_mission_proposals SET objective = 'changed'").update();
        })).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void ownerReviewsVersionsRegeneratesApprovesAndSupersedesAConfirmedMission() throws Exception {
        var discoveryId = createDiscovery();
        submit(discoveryId, "Support handles payment failures.");
        worker.runNext();
        submit(discoveryId, "The billing manager owns retry decisions.");
        worker.runNext();
        submit(discoveryId, "Complete proposal with required items, boundaries, context, and data use.");
        worker.runNext();
        worker.runNext();
        var proposalId = jdbc.sql("SELECT id FROM interview_mission_proposals").query(UUID.class).single();

        var confirmation = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/proposals/{id}/confirm", proposalId)
                        .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf()))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        var version1 = redirectedId(confirmation);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/missions/{id}", version1).with(user(EMAIL).roles("INVESTIGATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ready for approval")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ordered Investigation Items")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Approval sends nothing")));
        assertThat(jdbc.sql("SELECT status FROM interview_mission_proposals WHERE id = ?")
                .param(proposalId).query(String.class).single()).isEqualTo("confirmed");

        var version2 = redirectedId(mvc.perform(missionEdit(version1, "Edited objective", "Edited outcome",
                        "Edited shared context", "1 | Represented ambiguity"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl());
        assertThat(version2).isNotEqualTo(version1);
        assertThat(jdbc.sql("SELECT version || ':' || status || ':' || objective FROM interview_missions ORDER BY version")
                .query(String.class).list()).containsExactly(
                        "1:superseded:Understand failed card-payment retry decisions.",
                        "2:draft:Edited objective");
        assertThat(jdbc.sql("""
                SELECT source_kind FROM mission_element_provenance
                WHERE interview_mission_id = ? AND element_kind = 'objective'
                """).param(version2).query(String.class).list()).containsExactly("investigator_edit");
        assertThat(jdbc.sql("""
                SELECT source_kind FROM mission_element_provenance
                WHERE interview_mission_id = ? AND element_kind = 'expected_commitment'
                """).param(version2).query(String.class).list()).containsExactly("investigator_message");

        var version3 = redirectedId(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/missions/{id}/regenerate", version2)
                        .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf())
                        .param("proposalId", proposalId.toString()).param("section", "objective"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl());
        assertThat(jdbc.sql("SELECT objective || ':' || desired_outcome FROM interview_missions WHERE id = ?")
                .param(version3).query(String.class).single())
                .isEqualTo("Understand failed card-payment retry decisions.:Edited outcome");

        var version4 = redirectedId(mvc.perform(missionEdit(version3, "", "Edited outcome",
                        "Edited shared context", "1 | Represented ambiguity"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/missions/{id}", version4).with(user(EMAIL).roles("INVESTIGATOR")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Not ready for approval")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("objective")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/missions/{id}/approve", version4)
                        .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf()))
                .andExpect(status().isBadRequest());

        var version5 = redirectedId(mvc.perform(missionEdit(version4, "Ready objective", "Edited outcome",
                        "Edited shared context", "1 | Represented ambiguity"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/missions/{id}/approve", version5)
                        .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/missions/" + version5));
        assertThat(jdbc.sql("SELECT status FROM interview_missions WHERE id = ?")
                .param(version5).query(String.class).single()).isEqualTo("approved");
        assertThat(jdbc.sql("SELECT count(*) FROM invitations").query(Integer.class).single()).isZero();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/missions/{id}/approve", version3)
                        .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf()))
                .andExpect(status().isBadRequest());
        jdbc.sql("""
                INSERT INTO users (id, email, email_verified_at)
                VALUES ('30000000-0000-0000-0000-000000000099', 'other@example.com', now())
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO memberships (id, organisation_id, user_id, role)
                VALUES ('20000000-0000-0000-0000-000000000099',
                        '10000000-0000-0000-0000-000000000001',
                        '30000000-0000-0000-0000-000000000099', 'investigator')
                ON CONFLICT DO NOTHING
                """).update();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/missions/{id}", version5).with(user("other@example.com").roles("INVESTIGATOR")))
                .andExpect(status().isForbidden());

        var version6 = redirectedId(mvc.perform(missionEdit(version5, "Changed after approval", "Edited outcome",
                        "Edited shared context", "1 | Represented ambiguity"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl());
        assertThat(jdbc.sql("SELECT status FROM interview_missions WHERE id = ?")
                .param(version5).query(String.class).single()).isEqualTo("superseded");
        assertThat(jdbc.sql("SELECT status FROM interview_missions WHERE id = ?")
                .param(version6).query(String.class).single()).isEqualTo("draft");
        assertThat(jdbc.sql("SELECT count(*) FROM invitations").query(Integer.class).single()).isZero();

        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL ROLE findworks_application").update();
            jdbc.sql("SELECT set_config('findworks.organisation_id', '10000000-0000-0000-0000-000000000001', true)")
                    .query(String.class).single();
            jdbc.sql("UPDATE interview_missions SET objective = 'mutated' WHERE id = ?").param(version1).update();
        })).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void invalidForeignProvenanceWritesNothingAndShapingCanContinue() throws Exception {
        var discoveryId = createDiscovery();
        submit(discoveryId, "Support handles payment failures.");
        var work = repository.claimNext();

        var complete = proposal(work.triggerMessageId());
        var incomplete = new MissionProposal(
                complete.objective(), null, complete.intendedInterviewee(), complete.intervieweeRelevance(),
                complete.contexts(), complete.boundaries(), complete.terminology(), complete.openingQuestions(),
                complete.completionCriteria(), complete.expectedCommitment(), complete.dataUseSummary(),
                complete.investigationItems(), complete.unresolvedAmbiguities());
        assertThatThrownBy(() -> repository.complete(work, incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing desired outcome");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_mission_proposals").query(Integer.class).single())
                .isZero();

        var otherDiscoveryId = createDiscovery();
        submit(otherDiscoveryId, "This belongs to another shaping session.");
        var foreignMessageId = jdbc.sql("""
                SELECT m.id FROM discovery_shaping_messages m
                JOIN discovery_shaping_sessions s ON s.id = m.shaping_session_id
                WHERE s.discovery_id = ?
                """).param(otherDiscoveryId).query(UUID.class).single();

        assertThatThrownBy(() -> repository.complete(work, proposal(foreignMessageId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid Investigator provenance");
        assertThat(jdbc.sql("SELECT count(*) FROM interview_mission_proposals").query(Integer.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM mission_proposal_contexts").query(Integer.class).single())
                .isZero();

        repository.complete(work, "Which outcome should this Discovery produce?");
        submit(discoveryId, "A clear retry policy.");
        assertThat(jdbc.sql("""
                SELECT count(*) FROM discovery_shaping_messages m
                JOIN discovery_shaping_sessions s ON s.id = m.shaping_session_id
                WHERE s.discovery_id = ?
                """).param(discoveryId).query(Integer.class).single()).isEqualTo(3);
    }

    private void submit(UUID discoveryId, String content) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/discoveries/{id}/shaping", discoveryId)
                        .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf())
                        .param("content", content))
                .andExpect(status().is3xxRedirection());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder missionEdit(
            UUID id, String objective, String desiredOutcome, String sharedContext, String ambiguities) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/missions/{id}/save", id)
                .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf())
                .param("objective", objective)
                .param("desiredOutcome", desiredOutcome)
                .param("intendedInterviewee", "Billing manager")
                .param("intervieweeRelevance", "Owns retry decisions")
                .param("sharedContext", sharedContext)
                .param("privateContext", "Private concern")
                .param("boundaries", "Payment failures only")
                .param("prohibitedTopics", "No salaries")
                .param("terminology", "retry | another payment attempt")
                .param("openingQuestions", "How are retries decided?")
                .param("completionCriteria", "Rules are explicit")
                .param("expectedCommitment", "20 minutes")
                .param("dataUseSummary", "Use for this Discovery")
                .param("itemAction", "keep")
                .param("itemPosition", "1")
                .param("itemGap", "Gap")
                .param("itemImportance", "Importance")
                .param("itemPriority", "high")
                .param("itemContext", "Context")
                .param("itemRequired", "true")
                .param("itemOutcomes", "unknown")
                .param("ambiguities", ambiguities);
    }

    private UUID redirectedId(String redirect) {
        return UUID.fromString(redirect.substring(redirect.lastIndexOf('/') + 1));
    }

    private MissionProposal proposal(UUID messageId) {
        var source = new MissionProposal.Source("investigator_message", List.of(messageId));
        var text = new MissionProposal.SourcedText("Value", source);
        return new MissionProposal(
                text, text, text, text,
                List.of(new MissionProposal.ContextEntry("shared", "Shared context", source)),
                List.of(
                        new MissionProposal.Boundary("boundary", "Boundary", source),
                        new MissionProposal.Boundary("prohibited_topic", "No prohibited topics", source)),
                List.of(),
                List.of(new MissionProposal.OpeningQuestion("Opening question?", source)),
                text, text, text,
                List.of(new MissionProposal.InvestigationItem(
                        "Gap", "Importance", "high", "Context", true,
                        List.of(new MissionProposal.AllowedOutcome("unknown", source)), source)),
                List.of());
    }

    private UUID createDiscovery() throws Exception {
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/discoveries").with(user(EMAIL).roles("INVESTIGATOR")).with(csrf())
                        .param("title", "Payment failures")
                        .param("objective", "Understand how failed card payments are handled"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse();
        return UUID.fromString(response.getRedirectedUrl().substring(response.getRedirectedUrl().lastIndexOf('/') + 1));
    }

    private static Path fakePi() {
        try {
            var script = Files.createTempFile("findworks-fake-pi-", ".sh");
            Files.writeString(script, """
                    #!/bin/sh
                    IFS= read -r input
                    id=$(printf '%s' "$input" | sed -E 's/.*"id":"([^"]+)".*/\\1/')
                    case "$input" in
                      *Complete*proposal*)
                        source=$(printf '%s' "$input" | grep -Eo '\\[[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\]' | tail -1 | tr -d '[]')
                        proposal='{"objective":{"value":"Understand failed card-payment retry decisions.","source":{"kind":"investigator_message","messageIds":["SOURCE"]}},"desiredOutcome":{"value":"Document consistent retry rules and exceptions.","source":{"kind":"investigator_message","messageIds":["SOURCE"]}},"intendedInterviewee":{"value":"Billing manager","source":{"kind":"investigator_message","messageIds":["SOURCE"]}},"intervieweeRelevance":{"value":"Owns retry decisions and checks account history.","source":{"kind":"investigator_message","messageIds":["SOURCE"]}},"contexts":[{"visibility":"shared","content":"Payment status and account history may be shared with the interviewee.","source":{"kind":"investigator_message","messageIds":["SOURCE"]}},{"visibility":"private","content":"Suspected staff performance concerns stay private to the Investigator.","source":{"kind":"investigator_message","messageIds":["SOURCE"]}}],"boundaries":[{"kind":"boundary","content":"Cover failed card-payment handling only.","source":{"kind":"investigator_message","messageIds":["SOURCE"]}},{"kind":"prohibited_topic","content":"Do not discuss salaries.","source":{"kind":"investigator_message","messageIds":["SOURCE"]}}],"terminology":[{"term":"retry","meaning":"Another manual payment attempt.","source":{"kind":"investigator_message","messageIds":["SOURCE"]}}],"openingQuestions":[{"question":"How does account history change a retry decision?","source":{"kind":"agent_proposal","messageIds":[]}},{"question":"What exceptions prevent a retry?","source":{"kind":"agent_proposal","messageIds":[]}}],"completionCriteria":{"value":"Retry rules and exceptions are explicit.","source":{"kind":"investigator_message","messageIds":["SOURCE"]}},"expectedCommitment":{"value":"20 minutes","source":{"kind":"investigator_message","messageIds":["SOURCE"]}},"dataUseSummary":{"value":"Use answers only for this Discovery.","source":{"kind":"investigator_message","messageIds":["SOURCE"]}},"investigationItems":[{"knowledgeGap":"When should Support retry a failed card payment?","importance":"Avoid inconsistent retries.","priority":"high","relevantContext":"The billing manager checks account history.","required":true,"allowedOutcomes":[{"kind":"supported_knowledge","source":{"kind":"investigator_message","messageIds":["SOURCE"]}},{"kind":"unknown","source":{"kind":"agent_proposal","messageIds":[]}},{"kind":"conflict","source":{"kind":"agent_proposal","messageIds":[]}}],"source":{"kind":"investigator_message","messageIds":["SOURCE"]}},{"knowledgeGap":"Who owns retries when the billing manager is away?","importance":"Keep decisions accountable.","priority":"high","relevantContext":"No deputy is yet identified.","required":true,"allowedOutcomes":[{"kind":"supported_knowledge","source":{"kind":"agent_proposal","messageIds":[]}},{"kind":"unknown","source":{"kind":"investigator_message","messageIds":["SOURCE"]}},{"kind":"ownership_gap","source":{"kind":"investigator_message","messageIds":["SOURCE"]}}],"source":{"kind":"investigator_message","messageIds":["SOURCE"]}}],"unresolvedAmbiguities":[{"content":"Who owns retries when the billing manager is away remains unresolved.","representedByItemPosition":1,"source":{"kind":"investigator_message","messageIds":["SOURCE"]}}]}'
                        proposal=$(printf '%s' "$proposal" | sed "s/SOURCE/$source/g")
                        printf '{"id":"%s","type":"response","command":"prompt","success":true}\n' "$id"
                        printf '{"type":"tool_execution_end","toolCallId":"tool-1","toolName":"propose_interview_mission","result":{"content":[],"details":{"proposal":%s}},"isError":false}\n' "$proposal"
                        printf '{"type":"agent_settled"}\n'
                        exit 0
                        ;;
                      *billing*manager*) question='What in the account history guides the billing manager?' ;;
                      *) question='Who in Support decides whether to retry a failed card payment?' ;;
                    esac
                    printf '{"id":"%s","type":"response","command":"prompt","success":true}\\n' "$id"
                    printf '{"type":"tool_execution_end","toolCallId":"tool-1","toolName":"ask_shaping_question","result":{"content":[],"details":{"question":"%s"}},"isError":false}\\n' "$question"
                    printf '{"type":"agent_settled"}\\n'
                    """);
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
            return script;
        } catch (Exception error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private record Message(String author, String content, Instant createdAt) {}
}
