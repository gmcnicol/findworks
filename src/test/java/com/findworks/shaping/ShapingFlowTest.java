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
import java.util.UUID;
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
