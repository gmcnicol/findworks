package com.findworks;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class PilotShellIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("findworks")
            .withUsername("findworks")
            .withPassword("findworks");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void verified_investigator_can_sign_in_and_load_empty_home() throws Exception {
        var login = mockMvc.perform(post("/signin")
                        .param("username", "gareth@example.com")
                        .param("password", "findworks")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/app"))
                .andReturn();

        var session = (MockHttpSession) login.getRequest().getSession(false);

        mockMvc.perform(get("/app").session(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your Discoveries")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No Discoveries yet")));

        mockMvc.perform(get("/api/discoveries").session(session).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void inactive_and_cross_organization_accounts_fail_closed() throws Exception {
        mockMvc.perform(post("/signin")
                        .param("username", "inactive.user@example.com")
                        .param("password", "findworks")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/denied"));

        mockMvc.perform(post("/signin")
                        .param("username", "inactive.membership@example.com")
                        .param("password", "findworks")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/denied"));

        mockMvc.perform(post("/signin")
                        .param("username", "outsider@example.com")
                        .param("password", "findworks")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/denied"));
    }

    @Test
    void tenant_owned_rows_reject_cross_organization_references() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into discoveries (id, organization_id, owner_membership_id, title, objective)
                values (
                    '99999999-9999-9999-9999-999999999999',
                    '22222222-2222-2222-2222-222222222222',
                    'f1111111-1111-1111-1111-111111111111',
                    'Cross-org discovery',
                    'Should fail'
                )
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
