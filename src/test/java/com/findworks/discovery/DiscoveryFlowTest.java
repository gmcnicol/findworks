package com.findworks.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DiscoveryFlowTest {

    private static final String EMAIL = "investigator@findworks.local";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void investigatorSignsInCreatesAndResumesOnlyTheirDiscovery() throws Exception {
        var otherMembership = UUID.randomUUID();
        var otherUser = UUID.randomUUID();
        var foreignOrganisation = UUID.randomUUID();
        var foreignMembership = UUID.randomUUID();
        var foreignUser = UUID.randomUUID();
        var foreignDiscovery = UUID.randomUUID();
        inTransaction(() -> {
            jdbc.sql("SELECT set_config('findworks.organisation_id', '10000000-0000-0000-0000-000000000001', true)")
                    .query(String.class).single();
            jdbc.sql("INSERT INTO users (id, email, email_verified_at) VALUES (?, 'other@example.com', now())")
                    .param(otherUser).update();
            jdbc.sql("INSERT INTO memberships (id, organisation_id, user_id, role) VALUES (?, '10000000-0000-0000-0000-000000000001', ?, 'investigator')")
                    .params(otherMembership, otherUser).update();
        });
        inTransaction(() -> {
            jdbc.sql("SELECT set_config('findworks.organisation_id', ?, true)")
                    .param(foreignOrganisation.toString()).query(String.class).single();
            jdbc.sql("INSERT INTO organisations (id, name) VALUES (?, 'Other Organisation')")
                    .param(foreignOrganisation).update();
            jdbc.sql("INSERT INTO users (id, email, email_verified_at) VALUES (?, 'foreign@example.com', now())")
                    .param(foreignUser).update();
            jdbc.sql("INSERT INTO memberships (id, organisation_id, user_id, role) VALUES (?, ?, ?, 'investigator')")
                    .params(foreignMembership, foreignOrganisation, foreignUser).update();
            jdbc.sql("INSERT INTO discoveries (id, organisation_id, owner_membership_id, title, objective) VALUES (?, ?, ?, 'Foreign', 'Hidden')")
                    .params(foreignDiscovery, foreignOrganisation, foreignMembership).update();
        });
        var login = mvc.perform(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders
                        .formLogin().user(EMAIL).password("findworks"))
                .andExpect(authenticated().withUsername(EMAIL))
                .andExpect(redirectedUrl("/discoveries"))
                .andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);

        var created = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/discoveries").session(session).with(csrf())
                        .param("title", "Returns").param("objective", "Learn the real process"))
                .andExpect(status().is3xxRedirection()).andReturn();
        var location = created.getResponse().getRedirectedUrl();
        assertThat(location).startsWith("/discoveries/");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(location).session(session))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(location)
                        .with(user("other@example.com").roles("INVESTIGATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(location)
                        .with(user("foreign@example.com").roles("INVESTIGATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(location))
                .andExpect(status().is3xxRedirection());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/logout")
                        .session(session).with(csrf()))
                .andExpect(redirectedUrl("/login?logout"));
        var secondLogin = mvc.perform(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders
                        .formLogin().user(EMAIL).password("findworks"))
                .andExpect(authenticated()).andReturn();
        var secondSession = (MockHttpSession) secondLogin.getRequest().getSession(false);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/discoveries")
                        .session(secondSession))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Returns")));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(location).session(secondSession))
                .andExpect(status().isOk());

        var audit = new TransactionTemplate(transactions).execute(status -> {
            jdbc.sql("SET LOCAL ROLE findworks_application").update();
            jdbc.sql("SELECT set_config('findworks.organisation_id', '10000000-0000-0000-0000-000000000001', true)")
                    .query(String.class).single();
            return jdbc.sql("SELECT action, resource_kind FROM audit_records ORDER BY created_at")
                    .query((rs, row) -> rs.getString("action") + ":" + rs.getString("resource_kind")).list();
        });
        assertThat(audit).contains("investigator_authenticated:membership", "discovery_created:discovery");
        assertThat(UUID.fromString(location.substring(location.lastIndexOf('/') + 1))).isNotNull();
        var foreignVisible = new TransactionTemplate(transactions).execute(status -> {
            jdbc.sql("SET LOCAL ROLE findworks_application").update();
            jdbc.sql("SELECT set_config('findworks.organisation_id', '10000000-0000-0000-0000-000000000001', true)")
                    .query(String.class).single();
            return jdbc.sql("SELECT count(*) FROM discoveries WHERE id = ?")
                    .param(foreignDiscovery).query(Integer.class).single();
        });
        assertThat(foreignVisible).isZero();
    }

    @Test
    void unverifiedInvestigatorCannotSignIn() throws Exception {
        inTransaction(() -> jdbc.sql("UPDATE users SET email_verified_at = NULL WHERE email = ?").param(EMAIL).update());
        try {
            mvc.perform(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders
                            .formLogin().user(EMAIL).password("findworks"))
                    .andExpect(org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers
                            .unauthenticated());
        } finally {
            inTransaction(() -> jdbc.sql("UPDATE users SET email_verified_at = now() WHERE email = ?").param(EMAIL).update());
        }
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> work.run());
    }
}
