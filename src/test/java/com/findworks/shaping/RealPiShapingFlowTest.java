package com.findworks.shaping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@EnabledIfEnvironmentVariable(named = "RUN_PI_TESTS", matches = "true")
@SpringBootTest(properties = "findworks.shaping.worker-cron=-")
@AutoConfigureMockMvc
@Testcontainers
class RealPiShapingFlowTest {

    private static final String EMAIL = "investigator@findworks.local";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    ShapingWorker worker;

    @Autowired
    JdbcClient jdbc;

    @Test
    void realPiProducesOnePersistedAdaptiveQuestion() throws Exception {
        var created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/discoveries").with(user(EMAIL).roles("INVESTIGATOR")).with(csrf())
                        .param("title", "Payment failures")
                        .param("objective", "Understand how failed card payments are handled"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        var discoveryId = UUID.fromString(created.substring(created.lastIndexOf('/') + 1));
        var statement = "Support manually retries failed card payments before emailing the customer.";

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/discoveries/{id}/shaping", discoveryId)
                        .with(user(EMAIL).roles("INVESTIGATOR")).with(csrf())
                        .param("content", statement))
                .andExpect(status().is3xxRedirection());
        worker.runNext();
        worker.runNext();

        var messages = jdbc.sql("""
                SELECT author_kind, content FROM discovery_shaping_messages ORDER BY position
                """).query((rs, row) -> new Message(rs.getString("author_kind"), rs.getString("content"))).list();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content()).isEqualTo(statement);
        assertThat(messages.get(1).author()).isEqualTo("agent");
        assertThat(messages.get(1).content().toLowerCase())
                .containsAnyOf("support", "payment", "customer", "email", "retries");
        assertThat(jdbc.sql("SELECT status FROM shaping_runtime_work").query(String.class).single())
                .isEqualTo("succeeded");
    }

    private record Message(String author, String content) {}
}
