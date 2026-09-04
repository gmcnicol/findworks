package com.findworks;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
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
        registry.add("findworks.test-support", () -> "true");
        registry.add("findworks.operator-token", () -> "test-operator-token");
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your work")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Dashboard")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Discoveries")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Missions")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Findings")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Harness access")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No Discoveries yet")));

        mockMvc.perform(get("/api/discoveries").session(session).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void investigator_workspace_surfaces_owned_discoveries_missions_and_review_work() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var reviewUrl = submitted.path("review_url").asText();

        mockMvc.perform(get("/app").session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("item needs your attention")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Understand Ledgerling reconciliation")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Draft needs review")));
        mockMvc.perform(get("/app/discoveries").session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ledgerling reconciliation discovery")));
        mockMvc.perform(get("/app/missions").session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Review Mission")));
        mockMvc.perform(get("/app/findings").session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No findings yet")));
        mockMvc.perform(get(reviewUrl).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/app/missions\" aria-current=\"page\"")));
        mockMvc.perform(get("/oauth/grants").session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/oauth/grants\" aria-current=\"page\"")));
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

    @Test
    void operator_status_is_content_free_and_hidden_without_its_token() throws Exception {
        mockMvc.perform(get("/operator/status"))
                .andExpect(status().isNotFound());

        jdbcTemplate.update("""
                insert into email_outbox(id, organization_id, kind, recipient, encrypted_link, state)
                values (?, '11111111-1111-1111-1111-111111111111', 'INVITATION', 'email@example.com', ?, 'RUNNING')
                """, UUID.randomUUID(), new byte[] { 1 });

        mockMvc.perform(get("/operator/status").header("X-Operator-Token", "test-operator-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending_email").value(1))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"pending_email\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"failed_runtime_runs\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"overdue_deletions\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("email@example.com"))));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where actor_kind='OPERATOR' and action='OPERATOR_STATUS_READ'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void public_harness_can_authorize_list_discoveries_and_revoke_access() throws Exception {
        var access = authorizeHarness("findworks:read");
        var accessToken = access.token();
        var call = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"list_discoveries","arguments":{"contract_version":1}}}
                """;
        mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(call))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"jsonrpc":"2.0","id":1,"result":{"structuredContent":{"contract_version":1,"discoveries":[]},"isError":false}}
                        """, false));

        mockMvc.perform(post("/oauth/revoke")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", accessToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(call))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oauth_code_is_one_time_and_read_scope_cannot_write() throws Exception {
        var access = authorizeHarness("findworks:read");

        mockMvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", access.code())
                        .param("client_id", access.clientId())
                        .param("redirect_uri", "http://127.0.0.1:49152/callback")
                        .param("code_verifier", access.verifier()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"error\":\"invalid_grant\"}"));

        var writeCall = """
                {"jsonrpc":"2.0","id":21,"method":"tools/call","params":{"name":"create_discovery_with_draft_mission","arguments":{}}}
                """;
        mockMvc.perform(post("/mcp").header("Authorization", "Bearer " + access.token())
                        .contentType(MediaType.APPLICATION_JSON).content(writeCall))
                .andExpect(status().isUnauthorized());
        jdbcTemplate.update("update oauth_tokens set audience='https://wrong.example/mcp' where hash=?",
                com.findworks.platform.Ids.sha(access.token()));
        mockMvc.perform(post("/mcp").header("Authorization", "Bearer " + access.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"jsonrpc":"2.0","id":22,"method":"tools/call","params":{"name":"list_discoveries","arguments":{"contract_version":1}}}
                                """))
                .andExpect(status().isUnauthorized());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action='MCP_ACCESS_DENIED' and outcome='DENIED'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void oauth_refresh_token_rotates_and_revocation_ends_the_grant() throws Exception {
        var access = authorizeHarness("findworks:read");
        jdbcTemplate.update("update oauth_tokens set expires_at=now()-interval '1 second' where hash=?",
                com.findworks.platform.Ids.sha(access.token()));

        var refreshedBody = mockMvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("client_id", access.clientId())
                        .param("refresh_token", access.refreshToken()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var refreshed = tools.jackson.databind.json.JsonMapper.builder().build().readTree(refreshedBody);
        var accessToken = refreshed.path("access_token").asText();
        var refreshToken = refreshed.path("refresh_token").asText();
        org.assertj.core.api.Assertions.assertThat(accessToken).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(refreshToken).isNotBlank().isNotEqualTo(access.refreshToken());

        mockMvc.perform(post("/mcp").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"jsonrpc":"2.0","id":23,"method":"tools/call","params":{"name":"list_discoveries","arguments":{"contract_version":1}}}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "refresh_token")
                        .param("client_id", access.clientId())
                        .param("refresh_token", access.refreshToken()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/oauth/revoke")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("token", refreshToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/mcp").header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"jsonrpc":"2.0","id":24,"method":"tools/call","params":{"name":"list_discoveries","arguments":{"contract_version":1}}}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oauth_registration_is_bounded_per_remote_address() throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            mockMvc.perform(post("/oauth/register")
                            .with(request -> { request.setRemoteAddr("192.0.2.47"); return request; })
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"redirect_uris":["http://127.0.0.1:49152/callback"],"scope":"findworks:read"}
                                    """))
                    .andExpect(status().isCreated());
        }
        mockMvc.perform(post("/oauth/register")
                        .with(request -> { request.setRemoteAddr("192.0.2.47"); return request; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"redirect_uris":["http://127.0.0.1:49152/callback"],"scope":"findworks:read"}
                                """))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void harness_submission_creates_one_draft_and_a_working_review_handoff() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submissionId = UUID.randomUUID();
        var call = """
                {
                  "jsonrpc":"2.0","id":2,"method":"tools/call",
                  "params":{"name":"create_discovery_with_draft_mission","arguments":%s}
                }
                """.formatted(validMissionPayload(submissionId));

        var first = mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + access.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(call))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var result = tools.jackson.databind.json.JsonMapper.builder().build().readTree(first)
                .path("result").path("structuredContent");
        org.assertj.core.api.Assertions.assertThat(result.path("outcome").asText()).isEqualTo("created");
        org.assertj.core.api.Assertions.assertThat(result.path("lifecycle").asText()).isEqualTo("DRAFT");
        org.assertj.core.api.Assertions.assertThat(result.path("mission_version").asInt()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(result.path("administrative_label").asText()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(result.path("created_at").asText()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from domain_outbox where event_type='MISSION_DRAFT_IMPORTED' and resource_id=?",
                Integer.class, UUID.fromString(result.path("mission_id").asText()))).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action='MISSION_DRAFT_IMPORTED' and resource_id=?",
                Integer.class, UUID.fromString(result.path("discovery_id").asText()))).isEqualTo(1);

        mockMvc.perform(get(result.path("review_url").asText()).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Understand Ledgerling reconciliation")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Accountant")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Draft · Version 1")));

        mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + access.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(call))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("replayed")));

        mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + access.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(call.replace("Ledgerling reconciliation discovery", "Changed after submission")))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("submission_conflict")));

        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var addArguments = (tools.jackson.databind.node.ObjectNode) mapper.readTree(validMissionPayload(UUID.randomUUID()));
        addArguments.remove("discovery_title");
        addArguments.remove("discovery_objective");
        addArguments.put("discovery_id", result.path("discovery_id").asText());
        var added = mcpCall(access, "add_draft_mission", mapper.writeValueAsString(addArguments));
        org.assertj.core.api.Assertions.assertThat(added.path("outcome").asText()).isEqualTo("created");
        org.assertj.core.api.Assertions.assertThat(added.path("discovery_id").asText()).isEqualTo(result.path("discovery_id").asText());
    }

    @Test
    void invalid_mission_reports_every_safe_violation_without_creating_a_discovery() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var call = """
                {
                  "jsonrpc":"2.0","id":3,"method":"tools/call",
                  "params":{"name":"create_discovery_with_draft_mission","arguments":{
                    "contract_version":1,
                    "submission_id":"%s",
                    "discovery_title":"Ledgerling discovery",
                    "discovery_objective":"Learn the workflow.",
                    "unexpected":"must be rejected",
                    "mission":{
                      "objective":"",
                      "desired_outcome":"Know the decision points.",
                      "intended_interviewee_role":"Accountant",
                      "intended_interviewee_relevance":"Performs reconciliation.",
                      "investigation_items":[],
                      "shared_context":[],
                      "boundaries":[],
                      "prohibited_topics":[],
                      "terminology":[],
                      "opening_questions":[],
                      "completion_criteria":[],
                      "expected_commitment_minutes":20,
                      "data_use_summary":"Reviewed findings may be exported."
                    },
                    "origins":[],
                    "project_references":[],
                    "confirmations":{
                      "exact_payload_reviewed":true,
                      "intentional_sharing":true,
                      "no_recipient_or_private_context":true,
                      "material_agent_proposals_confirmed":true
                    }
                  }}
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + access.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(call))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("validation_failed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/unexpected")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/mission/objective")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/mission/investigation_items")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/origins")));

        mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + access.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"list_discoveries","arguments":{"contract_version":1}}}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"jsonrpc":"2.0","id":4,"result":{"structuredContent":{"contract_version":1,"discoveries":[]},"isError":false}}
                        """, false));
    }

    @Test
    void investigator_reviews_approves_renames_and_supersedes_an_exact_mission_version() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var missionId = submitted.path("mission_id").asText();
        var reviewUrl = submitted.path("review_url").asText();

        mockMvc.perform(post("/app/missions/{missionId}/approve", missionId)
                        .session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1"))
                .andExpect(status().isConflict());

        for (int checkpoint = 1; checkpoint <= 5; checkpoint++) {
            mockMvc.perform(post("/app/missions/{missionId}/review/checkpoints/{checkpoint}", missionId, checkpoint)
                            .session(access.session())
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                            .param("version", "1"))
                    .andExpect(status().is3xxRedirection());
        }

        mockMvc.perform(post("/app/missions/{missionId}/approve", missionId)
                        .session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get(reviewUrl).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Approved · Version 1")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No invitation has been sent")));

        mockMvc.perform(post("/app/missions/{missionId}/label", missionId)
                        .session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("label", "Monthly reconciliation decisions"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get(reviewUrl).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Monthly reconciliation decisions")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Approved · Version 1")));

        mockMvc.perform(post("/app/missions/{missionId}/edit", missionId)
                        .session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1")
                        .param("field", "objective")
                        .param("value", "Understand exception handling in monthly reconciliation"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get(reviewUrl).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Draft · Version 2")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Understand exception handling in monthly reconciliation")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Approval superseded")));

        mockMvc.perform(post("/app/missions/{missionId}/items/0/edit", missionId)
                        .session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "2").param("gap", "Which exceptions require Gareth?")
                        .param("why", "Automation must stop safely.").param("priority", "HIGH")
                        .param("required", "true").param("context", "Monthly import reconciliation")
                        .param("sufficient", "One recent exception and its decision"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/app/missions/{missionId}/entries/OPENING_QUESTION/0/edit", missionId)
                        .session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "3").param("value", "Show me the latest exception you decided."))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get(reviewUrl).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Draft · Version 4")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Which exceptions require Gareth?")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Show me the latest exception you decided.")));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                select count(*) from mission_origins where mission_id=? and version=4 and authority='DIRECT_EDIT'
                  and pointer in ('/mission/objective','/mission/investigation_items/0','/mission/opening_questions/0')
                """, Integer.class, UUID.fromString(missionId))).isEqualTo(3);
    }

    @Test
    void approved_mission_invites_interviewee_for_informed_explicit_begin() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var missionId = submitted.path("mission_id").asText();
        approveMission(access, missionId, 1);

        mockMvc.perform(post("/app/missions/{missionId}/invite", missionId)
                        .session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1")
                        .param("name", "Clare")
                        .param("email", "clare@example.com")
                        .param("delivery", "email"))
                .andExpect(status().is3xxRedirection());

        var email = mockMvc.perform(get("/test/emails/latest").param("recipient", "clare@example.com"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var invitationLink = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(email).path("link").asText();

        mockMvc.perform(get(invitationLink))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Continue to invitation")));
        var redemption = mockMvc.perform(post(invitationLink)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/interview"))
                .andReturn();
        var interviewCookie = redemption.getResponse().getCookie("findworks_interview");
        org.assertj.core.api.Assertions.assertThat(interviewCookie).isNotNull();
        org.assertj.core.api.Assertions.assertThat(interviewCookie.isHttpOnly()).isTrue();

        var introduction = mockMvc.perform(get("/interview").cookie(interviewCookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Gareth")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("FindWorks Pilot")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Understand Ledgerling reconciliation")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("20 minutes")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("90 days")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("privacy@findworks.test")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Begin interview")))
                .andReturn();
        var participantSession = (MockHttpSession) introduction.getRequest().getSession(false);

        mockMvc.perform(post("/interview/begin")
                        .session(participantSession)
                        .cookie(interviewCookie)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/interview"));
        mockMvc.perform(get("/interview").session(participantSession).cookie(interviewCookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Walk me through the last mismatch you resolved.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your answer")));

        mockMvc.perform(get(invitationLink))
                .andExpect(status().isGone())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ask Gareth to reissue access")));
    }

    @Test
    void investigator_generates_a_manual_invitation_link_to_copy_and_send() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var missionId = submitted.path("mission_id").asText();
        approveMission(access, missionId, 1);

        var generated = mockMvc.perform(post("/app/missions/{missionId}/invite", missionId)
                        .session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1")
                        .param("name", "Clare")
                        .param("email", "Clare@example.com")
                        .param("delivery", "manual"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Copy invitation link")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("clare@example.com")))
                .andReturn();

        var html = generated.getResponse().getContentAsString();
        var linkMatch = java.util.regex.Pattern.compile("value=\"(http://127\\.0\\.0\\.1:8080/invite/[^\"]+)\"").matcher(html);
        org.assertj.core.api.Assertions.assertThat(linkMatch.find()).isTrue();
        var invitationLink = linkMatch.group(1);

        mockMvc.perform(get("/test/emails/latest").param("recipient", "clare@example.com"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(submitted.path("review_url").asText()).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("An invitation link is active")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(invitationLink))));

        // Messaging clients commonly fetch links to build previews. A preview must not
        // consume the invitation before the interviewee explicitly redeems it.
        mockMvc.perform(get(invitationLink))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Continue to invitation")));
        mockMvc.perform(get(invitationLink))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Continue to invitation")));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select redeemed_at is null from invitations where recipient_email='clare@example.com'", Boolean.class)).isTrue();

        mockMvc.perform(post(invitationLink)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/interview"));
        mockMvc.perform(get(invitationLink)).andExpect(status().isGone());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action='MANUAL_INVITATION_LINK_GENERATED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void invitation_redemption_is_bounded_without_storing_the_remote_address() throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) mockMvc.perform(get("/invite/not-a-token")
                        .with(request -> { request.setRemoteAddr("192.0.2.52"); return request; }))
                .andExpect(status().isGone());
        mockMvc.perform(get("/invite/not-a-token")
                        .with(request -> { request.setRemoteAddr("192.0.2.52"); return request; }))
                .andExpect(status().isTooManyRequests());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from invitation_access_attempts where remote_hash=?", Integer.class,
                com.findworks.platform.Ids.sha("192.0.2.52"))).isEqualTo(31);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action='INVITATION_REDEMPTION_RATE_LIMITED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void accepted_answer_and_runtime_work_commit_before_adaptive_question() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var missionId = submitted.path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "runtime@example.com");

        mockMvc.perform(post("/interview/answer")
                        .session(participant.session())
                        .cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "The finance analyst checks the source ledger before Gareth approves the correction."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/interview"));

        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Answer accepted")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("The finance analyst checks the source ledger")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Preparing your next question")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Pi RPC"))));

        var persisted = jdbcTemplate.queryForMap("""
                select e.exact_text,e.question_id,e.participant_id,r.state,r.expected_revision,s.revision
                from evidence e join runtime_runs r on r.evidence_id=e.id join interview_sessions s on s.id=e.session_id
                where e.session_id=?
                """, participant.sessionId());
        org.assertj.core.api.Assertions.assertThat(persisted.get("exact_text"))
                .isEqualTo("The finance analyst checks the source ledger before Gareth approves the correction.");
        org.assertj.core.api.Assertions.assertThat(persisted.get("question_id")).isNotNull();
        org.assertj.core.api.Assertions.assertThat(persisted.get("participant_id")).isNotNull();
        org.assertj.core.api.Assertions.assertThat(persisted.get("state")).isEqualTo("PENDING");
        org.assertj.core.api.Assertions.assertThat(persisted.get("expected_revision")).isEqualTo(persisted.get("revision"));

        mockMvc.perform(post("/test/runtime/run-once"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"event\":\"question_ready\"}"));

        for (int refresh = 0; refresh < 2; refresh++) {
            mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("The finance analyst checks the source ledger")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("What does the finance analyst check in the source ledger?")));
        }
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from questions where session_id=? and active", Integer.class, participant.sessionId())).isEqualTo(1);
    }

    @Test
    void interview_supports_pause_structured_evidence_unknown_owner_and_confirmed_completion() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var missionId = submitted.path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "routes@example.com");

        mockMvc.perform(post("/interview/pause").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select state from interview_sessions where id=?", String.class, participant.sessionId())).isEqualTo("PAUSED");
        mockMvc.perform(post("/interview/resume").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        jdbcTemplate.update("update questions set response_mode='YES_NO_PARTLY' where session_id=? and active", participant.sessionId());
        jdbcTemplate.update("""
                insert into question_options
                select id,'yes',0,'Yes' from questions where session_id=? and active
                union all select id,'no',1,'No' from questions where session_id=? and active
                union all select id,'partly',2,'Partly' from questions where session_id=? and active
                """, participant.sessionId(), participant.sessionId(), participant.sessionId());

        mockMvc.perform(post("/interview/structured-answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("optionId", "partly").param("optionLabel", "Partly")
                        .param("explanation", "Only unmatched entries need approval."))
                .andExpect(status().is3xxRedirection());
        var evidence = jdbcTemplate.queryForMap(
                "select option_id,option_label,explanation,exact_text from evidence where session_id=?", participant.sessionId());
        org.assertj.core.api.Assertions.assertThat(evidence).containsEntry("option_id", "partly")
                .containsEntry("option_label", "Partly")
                .containsEntry("explanation", "Only unmatched entries need approval.");

        mockMvc.perform(post("/test/runtime/run-once")).andExpect(status().isOk());
        mockMvc.perform(post("/interview/another-owner").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("owner", "Treasury lead").param("reason", "They decide which mismatch is acceptable."))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Treasury lead")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Confirm and finish")));

        var unresolved = jdbcTemplate.queryForMap("""
                select u.kind,u.owner,u.acknowledged,r.coverage
                from unresolved u join investigation_results r on r.id=u.result_id
                where r.session_id=?
                """, participant.sessionId());
        org.assertj.core.api.Assertions.assertThat(unresolved).containsEntry("kind", "OWNERSHIP_GAP")
                .containsEntry("owner", "Treasury lead").containsEntry("coverage", "OWNERSHIP_GAP");

        mockMvc.perform(post("/interview/confirm-completion").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Thank you")));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select state from interview_sessions where id=?", String.class, participant.sessionId())).isEqualTo("COMPLETED");
        mockMvc.perform(post("/test/extraction/run-once")).andExpect(status().isOk());
        var unresolvedId = jdbcTemplate.queryForObject(
                "select u.id from unresolved u join investigation_results r on r.id=u.result_id where r.session_id=?", UUID.class, participant.sessionId());
        mockMvc.perform(post("/app/findings/{sessionId}/unresolved/{unresolvedId}/acknowledge", participant.sessionId(), unresolvedId)
                        .session(access.session()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/app/findings/{sessionId}/accept", participant.sessionId()).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).param("version", "1"))
                .andExpect(status().is3xxRedirection());
        var exported = mcpCall(access, "get_reviewed_discovery_context", """
                {"contract_version":1,"discovery_id":"%s"}
                """.formatted(submitted.path("discovery_id").asText()));
        org.assertj.core.api.Assertions.assertThat(exported.toString()).contains("unresolved_evidence_excerpt")
                .contains("They decide which mismatch is acceptable.")
                .contains("unresolved_source_url").doesNotContain("#evidence-null")
                .doesNotContain("routes@example.com");
    }

    @Test
    void completed_interview_extracts_reviewable_evidence_backed_findings_and_publishes_exact_package() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var missionId = submitted.path("mission_id").asText();
        approveMission(access, missionId, 1);
        for (int position = 1; position <= 10; position++) jdbcTemplate.update(
                "insert into investigation_items values(?,?,1,?,'Optional gap '||?,'Context','LOW',false,'Context','Any evidence')",
                UUID.randomUUID(), UUID.fromString(missionId), position, position);
        var participant = beginInterview(access, missionId, "findings@example.com");

        answerAndRun(participant, "The finance analyst checks the imported amount against the source ledger.");
        answerAndRun(participant, "A mismatch needs Gareth's approval when the source ledger is also unclear.");
        mockMvc.perform(post("/interview/confirm-completion").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/app/findings/{sessionId}", participant.sessionId()).session(access.session()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Extraction pending")));
        mockMvc.perform(post("/test/extraction/run-once"))
                .andExpect(status().isOk()).andExpect(content().json("{\"event\":\"findings_ready\"}"));
        var review = mockMvc.perform(get("/app/findings/{sessionId}", participant.sessionId()).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("A mismatch needs Gareth&#39;s approval")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Open source context")))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(review).doesNotContain("findings@example.com");

        var knowledgeId = jdbcTemplate.queryForObject("select id from knowledge_items where package_id=(select id from findings_packages where session_id=?)", UUID.class, participant.sessionId());
        var packageIdForLinks = jdbcTemplate.queryForObject("select id from findings_packages where session_id=?", UUID.class, participant.sessionId());
        var resultIdForLinks = jdbcTemplate.queryForObject("select result_id from knowledge_items where id=?", UUID.class, knowledgeId);
        var evidenceIdForLinks = jdbcTemplate.queryForObject("select evidence_id from knowledge_evidence where knowledge_id=? limit 1", UUID.class, knowledgeId);
        var supportedId = UUID.randomUUID();
        var rejectedId = UUID.randomUUID();
        jdbcTemplate.update("insert into knowledge_items values(?,(select organization_id from findings_packages where id=?),?,?,1,1)",
                supportedId, packageIdForLinks, resultIdForLinks, packageIdForLinks);
        jdbcTemplate.update("insert into knowledge_versions values(?,1,'RULE','The source ledger qualifies the approval rule.','ACCEPTED',true,null)", supportedId);
        jdbcTemplate.update("insert into knowledge_evidence values(?,1,?,'A mismatch needs Gareth''s approval',0,35)", supportedId, evidenceIdForLinks);
        jdbcTemplate.update("insert into knowledge_items values(?,(select organization_id from findings_packages where id=?),?,?,1,1)",
                rejectedId, packageIdForLinks, resultIdForLinks, packageIdForLinks);
        jdbcTemplate.update("insert into knowledge_versions values(?,1,'FACT','Rejected private proposal','REJECTED',false,null)", rejectedId);
        jdbcTemplate.update("insert into knowledge_links values(?,?,'SUPPORTS'),(?,?,'QUALIFIES')",
                knowledgeId, supportedId, supportedId, rejectedId);
        mockMvc.perform(post("/app/findings/{sessionId}/knowledge/{knowledgeId}/correct", participant.sessionId(), knowledgeId)
                        .session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("claim", "Gareth approves a mismatch only when the source ledger is unclear."))
                .andExpect(status().is3xxRedirection());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select review_state from knowledge_versions where knowledge_id=? and version=2", String.class, knowledgeId)).isEqualTo("ACCEPTED");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select review_state from knowledge_versions where knowledge_id=? and version=1", String.class, knowledgeId)).isEqualTo("CORRECTED");

        mockMvc.perform(post("/app/findings/{sessionId}/accept", participant.sessionId()).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1").param("notes", "Ready for Ledgerling implementation."))
                .andExpect(status().is3xxRedirection());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select notes from package_versions where package_id=? and version=1", String.class, packageIdForLinks))
                .isEqualTo("Ready for Ledgerling implementation.");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from publications where invalidated_at is null", Integer.class)).isEqualTo(1);

        var listed = mcpCall(access, "list_discoveries", "{\"contract_version\":1}");
        org.assertj.core.api.Assertions.assertThat(listed.path("discoveries").get(0).path("reviewed_context_status").asText())
                .isEqualTo("PUBLISHED");
        var reviewed = mcpCall(access, "get_reviewed_discovery_context", """
                {"contract_version":1,"discovery_id":"%s"}
                """.formatted(submitted.path("discovery_id").asText()));
        org.assertj.core.api.Assertions.assertThat(reviewed.toString())
                .contains("Gareth approves a mismatch only when the source ledger is unclear.")
                .contains("ACCEPTED").contains("corrects_version").contains("mission_version").contains("evidence_excerpt")
                .contains("SUPPORTS").doesNotContain("QUALIFIES").doesNotContain("Rejected private proposal")
                .doesNotContain("findings@example.com").doesNotContain("opening_questions").doesNotContain("data_use_summary");
        org.assertj.core.api.Assertions.assertThat(reviewed.path("snapshot_cursor").asText()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(reviewed.path("next_cursor").asText()).isNotBlank();
        org.assertj.core.api.Assertions.assertThat(distinctResultCount(reviewed)).isEqualTo(10);

        var packageId = UUID.fromString(reviewed.path("groups").get(0).path("findings_package_id").asText());
        var organizationId = jdbcTemplate.queryForObject("select organization_id from findings_packages where id=?", UUID.class, packageId);
        jdbcTemplate.update("update publications set invalidated_at=now() where invalidated_at is null and discovery_id=?",
                UUID.fromString(submitted.path("discovery_id").asText()));
        jdbcTemplate.update("insert into package_versions values(?,?,2,'ACCEPTED',null,now())", packageId, organizationId);
        jdbcTemplate.update("insert into package_results select ?,2,result_id from package_results where package_id=? and package_version=1 limit 1", packageId, packageId);
        jdbcTemplate.update("update findings_packages set current_version=2,accepted_version=2 where id=?", packageId);
        jdbcTemplate.update("insert into publications values(?,?,?,?,2,now(),null)", UUID.randomUUID(), organizationId,
                UUID.fromString(submitted.path("discovery_id").asText()), packageId);

        var next = mcpCall(access, "get_reviewed_discovery_context", """
                {"contract_version":1,"discovery_id":"%s","cursor":"%s"}
                """.formatted(submitted.path("discovery_id").asText(), reviewed.path("next_cursor").asText()));
        org.assertj.core.api.Assertions.assertThat(distinctResultCount(next)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(next.path("groups").get(0).path("findings_package_version").asInt()).isEqualTo(1);
        jdbcTemplate.update("update export_cursors set expires_at=now()-interval '1 second'");
        org.assertj.core.api.Assertions.assertThat(mcpCall(access, "get_reviewed_discovery_context", """
                {"contract_version":1,"discovery_id":"%s","cursor":"%s"}
                """.formatted(submitted.path("discovery_id").asText(), reviewed.path("next_cursor").asText())).path("code").asText())
                .isEqualTo("cursor_invalid");
    }

    @Test
    void investigator_can_reject_an_exact_findings_package_with_required_follow_up() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var missionId = submitted.path("mission_id").asText();
        var discoveryId = UUID.fromString(submitted.path("discovery_id").asText());
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "rejected-package@example.com");

        mockMvc.perform(post("/interview/do-not-know").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("explanation", "The reconciliation owner must answer this."))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/interview/confirm-completion").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/test/extraction/run-once")).andExpect(status().isOk());

        mockMvc.perform(post("/app/findings/{sessionId}/reject", participant.sessionId()).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1").param("notes", "Follow up with the reconciliation owner."))
                .andExpect(status().is3xxRedirection());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForMap("""
                        select pv.state,pv.notes,d.retention_due_at
                        from findings_packages p join package_versions pv on pv.package_id=p.id and pv.version=1
                        join interview_sessions s on s.id=p.session_id join discoveries d on d.id=s.discovery_id
                        where p.session_id=?
                        """, participant.sessionId()))
                .containsEntry("state", "REJECTED")
                .containsEntry("notes", "Follow up with the reconciliation owner.");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select retention_due_at is not null from discoveries where id=?", Boolean.class, discoveryId)).isTrue();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from publications where discovery_id=?", Integer.class, discoveryId)).isZero();
        mockMvc.perform(post("/app/findings/{sessionId}/reject", participant.sessionId()).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1").param("notes", "Try again."))
                .andExpect(status().isConflict());
    }

    @Test
    void conflict_membership_survives_findings_review_and_reviewed_context_export() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var missionId = submitted.path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "conflict-members@example.com");

        answerAndRun(participant, "Every mismatch needs approval.");
        answerAndRun(participant, "This contradicts my first answer: routine mismatches need no approval.");
        mockMvc.perform(post("/interview/confirm-completion").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/test/extraction/run-once")).andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from conflict_members where conflict_id=(select u.id from unresolved u join investigation_results r on r.id=u.result_id where r.session_id=? and u.kind='CONFLICT')",
                Integer.class, participant.sessionId())).isEqualTo(2);
        var knowledgeIds = jdbcTemplate.queryForList("select id from knowledge_items where package_id=(select id from findings_packages where session_id=?)", UUID.class, participant.sessionId());
        for (var knowledgeId : knowledgeIds) mockMvc.perform(post("/app/findings/{sessionId}/knowledge/{knowledgeId}/decision", participant.sessionId(), knowledgeId)
                        .session(access.session()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("decision", "ACCEPTED"))
                .andExpect(status().is3xxRedirection());
        var unresolvedId = jdbcTemplate.queryForObject(
                "select u.id from unresolved u join investigation_results r on r.id=u.result_id where r.session_id=? and u.kind='CONFLICT'", UUID.class, participant.sessionId());
        mockMvc.perform(post("/app/findings/{sessionId}/unresolved/{unresolvedId}/acknowledge", participant.sessionId(), unresolvedId)
                        .session(access.session()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/app/findings/{sessionId}/accept", participant.sessionId()).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()).param("version", "1"))
                .andExpect(status().is3xxRedirection());

        var reviewed = mcpCall(access, "get_reviewed_discovery_context", """
                {"contract_version":1,"discovery_id":"%s"}
                """.formatted(submitted.path("discovery_id").asText()));
        org.assertj.core.api.Assertions.assertThat(reviewed.path("conflict_memberships").size()).isEqualTo(2);
    }

    @Test
    void invitation_reissue_revocation_expiry_and_termination_preserve_the_session_and_evidence() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var first = beginInterview(access, missionId, "recover@example.com");
        mockMvc.perform(post("/interview/answer").session(first.session()).cookie(first.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "This accepted answer must survive access recovery."))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/app/missions/{missionId}/invite", missionId).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1").param("name", "Clare").param("email", "recover@example.com")
                        .param("delivery", "email"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/interview").session(first.session()).cookie(first.cookie()))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from interview_sessions where mission_id=?", Integer.class, UUID.fromString(missionId))).isEqualTo(1);

        var latest = tools.jackson.databind.json.JsonMapper.builder().build().readTree(
                mockMvc.perform(get("/test/emails/latest").param("recipient", "recover@example.com"))
                        .andReturn().getResponse().getContentAsString()).path("link").asText();
        jdbcTemplate.update("update invitations set expires_at=now()-interval '1 second' where token_hash=?", com.findworks.platform.Ids.sha(latest.substring(latest.lastIndexOf('/') + 1)));
        mockMvc.perform(get(latest)).andExpect(status().isGone())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ask Gareth to reissue access")));

        mockMvc.perform(post("/app/interviews/{sessionId}/terminate", first.sessionId()).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select exact_text from evidence where session_id=?", String.class, first.sessionId()))
                .isEqualTo("This accepted answer must survive access recovery.");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select state from interview_sessions where id=?", String.class, first.sessionId())).isEqualTo("TERMINATED");

        mockMvc.perform(post("/app/interviews/{sessionId}/delete", first.sessionId()).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/interview").session(first.session()).cookie(first.cookie())).andExpect(status().isForbidden());
        jdbcTemplate.update("update deletion_ledger set purge_due_at=now()-interval '1 second' where resource_id=?", first.sessionId());
        mockMvc.perform(post("/operator/purge/run-once").header("X-Operator-Token", "test-operator-token"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from interview_sessions where id=?", Integer.class, first.sessionId())).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from participants where email='recover@example.com'", Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from investigation_items where mission_id=?", Integer.class, UUID.fromString(missionId))).isEqualTo(1);
    }

    @Test
    void runtime_exhaustion_and_extraction_failure_retry_without_duplicate_or_lost_work() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "faults@example.com");
        jdbcTemplate.update("update fault_controls set remaining=3 where kind='runtime'");

        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "Accepted once despite a failing model."))
                .andExpect(status().is3xxRedirection());
        for (int attempt = 1; attempt <= 3; attempt++) mockMvc.perform(post("/test/runtime/run-once")).andExpect(status().isOk());
        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Your answer is safe")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("do not need to repeat")));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from evidence where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);

        mockMvc.perform(post("/interview/retry").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/test/runtime/run-once")).andExpect(status().isOk())
                .andExpect(content().json("{\"event\":\"question_ready\"}"));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from questions where session_id=? and sequence=2", Integer.class, participant.sessionId())).isEqualTo(1);

        answerAndRun(participant, "A second answer provides a supported outcome.");
        mockMvc.perform(post("/interview/confirm-completion").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        jdbcTemplate.update("update fault_controls set remaining=1 where kind='extraction'");
        mockMvc.perform(post("/test/extraction/run-once")).andExpect(status().isOk())
                .andExpect(content().json("{\"event\":\"findings_failed\"}"));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from findings_packages where session_id=?", Integer.class, participant.sessionId())).isZero();
        mockMvc.perform(post("/test/extraction/run-once")).andExpect(status().isOk())
                .andExpect(content().json("{\"event\":\"findings_ready\"}"));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from findings_packages where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);
    }

    @Test
    void process_death_rebuilds_a_matching_checkpoint_once_and_stale_work_cannot_mutate() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "checkpoint@example.com");
        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "An accepted answer survives a worker process death."))
                .andExpect(status().is3xxRedirection());
        var run = jdbcTemplate.queryForMap("select id,expected_revision from runtime_runs where session_id=?", participant.sessionId());
        jdbcTemplate.update("insert into runtime_checkpoints values(?,?,99,99,'READY','private://wrong',?,now())",
                run.get("id"), UUID.randomUUID(), "0".repeat(64));
        jdbcTemplate.update("update fault_controls set remaining=1 where kind='runtime_death'");
        mockMvc.perform(post("/test/runtime/run-once").param("sessionId", participant.sessionId().toString()))
                .andExpect(status().isOk()).andExpect(content().json("{\"event\":\"runtime_restarting\"}"));
        mockMvc.perform(post("/test/runtime/run-once").param("sessionId", participant.sessionId().toString()))
                .andExpect(status().isOk()).andExpect(content().json("{\"event\":\"question_ready\"}"));
        var checkpoint = jdbcTemplate.queryForMap("select session_id,mission_version,expected_revision,state from runtime_checkpoints where run_id=?", run.get("id"));
        org.assertj.core.api.Assertions.assertThat(checkpoint).containsEntry("session_id", participant.sessionId())
                .containsEntry("mission_version", 1).containsEntry("expected_revision", run.get("expected_revision"))
                .containsEntry("state", "COMMITTED");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select restart_count from runtime_runs where id=?", Integer.class, run.get("id"))).isEqualTo(1);

        var staleMission = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, staleMission, 1);
        var stale = beginInterview(access, staleMission, "stale@example.com");
        mockMvc.perform(post("/interview/answer").session(stale.session()).cookie(stale.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "This work will become stale."))
                .andExpect(status().is3xxRedirection());
        jdbcTemplate.update("update interview_sessions set revision=revision+1 where id=?", stale.sessionId());
        mockMvc.perform(post("/test/runtime/run-once").param("sessionId", stale.sessionId().toString()))
                .andExpect(status().isOk()).andExpect(content().json("{\"event\":\"runtime_failed\"}"));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from questions where session_id=?", Integer.class, stale.sessionId())).isEqualTo(1);
    }

    @Test
    void concurrent_sessions_keep_evidence_questions_runs_and_checkpoints_isolated() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var firstMission = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        var secondMission = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, firstMission, 1);
        approveMission(access, secondMission, 1);
        var first = beginInterview(access, firstMission, "concurrent-first@example.com");
        var second = beginInterview(access, secondMission, "concurrent-second@example.com");

        mockMvc.perform(post("/interview/answer").session(first.session()).cookie(first.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "First Session answer."))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/interview/answer").session(second.session()).cookie(second.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "Second Session answer."))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/test/runtime/run-once").param("sessionId", first.sessionId().toString())).andExpect(status().isOk());
        mockMvc.perform(post("/test/runtime/run-once").param("sessionId", second.sessionId().toString())).andExpect(status().isOk());

        for (var participant : java.util.List.of(first, second)) {
            org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from evidence where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from questions where session_id=?", Integer.class, participant.sessionId())).isEqualTo(2);
            org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from runtime_checkpoints c join runtime_runs r on r.id=c.run_id where r.session_id=? and c.session_id=?",
                    Integer.class, participant.sessionId(), participant.sessionId())).isEqualTo(1);
        }
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from evidence e join questions q on q.id=e.question_id where e.session_id<>q.session_id", Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(distinct location) from runtime_checkpoints where session_id in (?,?)", Integer.class,
                first.sessionId(), second.sessionId())).isEqualTo(2);
    }

    @Test
    void discovery_deletion_denies_immediately_then_purges_content_and_retains_only_bounded_tombstones() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var discoveryId = UUID.fromString(submitted.path("discovery_id").asText());
        var missionId = submitted.path("mission_id").asText();
        jdbcTemplate.update("insert into retention_warnings(discovery_id) values(?)", discoveryId);
        jdbcTemplate.update("""
                insert into email_outbox(id,organization_id,kind,recipient,encrypted_link,state)
                values(?,'11111111-1111-1111-1111-111111111111','RETENTION_WARNING','gareth@example.com',?,'COMPLETED')
                """, discoveryId, new byte[] { 1 });
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "delete-me@example.com");
        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "Sensitive answer removed by the bounded purge."))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/app/discoveries/{discoveryId}/delete", discoveryId).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/app"));
        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(mcpCall(access, "list_discoveries", "{\"contract_version\":1}")
                .path("discoveries").isEmpty()).isTrue();
        var ledger = jdbcTemplate.queryForMap("select id,purge_due_at,backup_expiry_at from deletion_ledger where resource_id=?", discoveryId);
        org.assertj.core.api.Assertions.assertThat(ledger.get("backup_expiry_at")).isNotNull();

        jdbcTemplate.update("update deletion_ledger set purge_due_at=now()-interval '1 second' where resource_id=?", discoveryId);
        mockMvc.perform(post("/operator/purge/run-once").header("X-Operator-Token", "test-operator-token"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from discoveries where id=?", Integer.class, discoveryId)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from evidence where session_id=?", Integer.class, participant.sessionId())).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from test_emails where recipient='delete-me@example.com'", Integer.class)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from email_outbox where id=?", Integer.class, discoveryId)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from submissions where deleted_at is not null and discovery_id is null and mission_id is null", Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from deletion_ledger where resource_id=? and purged_at is not null and backup_expiry_at<=requested_at+interval '30 days'", Integer.class, discoveryId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where resource_id=? and expires_at<=created_at+interval '12 months 1 minute'", Integer.class, discoveryId)).isGreaterThan(0);
    }

    @Test
    void session_deletion_invalidates_findings_then_purges_its_subtree_and_unused_participant() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var discoveryId = UUID.fromString(submitted.path("discovery_id").asText());
        var missionId = submitted.path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "delete-session@example.com");
        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "Accepted Evidence retained until this Session is purged."))
                .andExpect(status().is3xxRedirection());

        var organizationId = jdbcTemplate.queryForObject("select organization_id from interview_sessions where id=?", UUID.class, participant.sessionId());
        var participantId = jdbcTemplate.queryForObject("select participant_id from interview_sessions where id=?", UUID.class, participant.sessionId());
        var packageId = UUID.randomUUID();
        jdbcTemplate.update("insert into findings_packages(id,organization_id,session_id,current_version,accepted_version) values(?,?,?,1,1)",
                packageId, organizationId, participant.sessionId());
        jdbcTemplate.update("insert into package_versions(package_id,organization_id,version,state) values(?,?,1,'ACCEPTED')", packageId, organizationId);
        jdbcTemplate.update("insert into publications(id,organization_id,discovery_id,package_id,package_version) values(?,?,?,?,1)",
                UUID.randomUUID(), organizationId, discoveryId, packageId);

        mockMvc.perform(post("/app/interviews/{sessionId}/delete", participant.sessionId()).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/app"));
        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from publications where package_id=? and invalidated_at is not null", Integer.class, packageId)).isEqualTo(1);

        jdbcTemplate.update("update deletion_ledger set purge_due_at=now()-interval '1 second' where resource_id=?", participant.sessionId());
        mockMvc.perform(post("/operator/purge/run-once").header("X-Operator-Token", "test-operator-token"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from interview_sessions where id=?", Integer.class, participant.sessionId())).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from findings_packages where id=?", Integer.class, packageId)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from participants where id=?", Integer.class, participantId)).isZero();
    }

    @Test
    void retention_warning_is_queued_once_for_the_owning_investigator() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var discoveryId = UUID.fromString(submitMission(access, UUID.randomUUID()).path("discovery_id").asText());
        jdbcTemplate.update("update discoveries set retention_due_at=now()+interval '13 days' where id=?", discoveryId);

        for (int run = 0; run < 2; run++) mockMvc.perform(post("/operator/retention/run-once")
                        .header("X-Operator-Token", "test-operator-token"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from retention_warnings where discovery_id=?", Integer.class, discoveryId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from test_emails where kind='RETENTION_WARNING' and recipient='gareth@example.com'", Integer.class)).isEqualTo(1);
    }

    @Test
    void turn_scoped_runtime_capability_commits_once_and_replays_only_the_stable_event() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "capability@example.com");
        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "The finance analyst checks the source ledger."))
                .andExpect(status().is3xxRedirection());
        var run = jdbcTemplate.queryForMap("select id,expected_revision from runtime_runs where session_id=?", participant.sessionId());
        var resultId = jdbcTemplate.queryForObject("select id from investigation_results where session_id=?", UUID.class, participant.sessionId());
        var evidenceId = jdbcTemplate.queryForObject("select id from evidence where session_id=?", UUID.class, participant.sessionId());
        var token = "turn-scoped-test-token";
        jdbcTemplate.update("insert into runtime_turn_credentials values(?,?,now()+interval '5 minutes',null)",
                com.findworks.platform.Ids.sha(token), run.get("id"));
        var body = """
                {"runId":"%s","expectedRevision":%s,"outcomes":[
                  {"resultId":"%s","coverage":"SUPPORTED","category":"RULE","evidenceIds":["%s"],"summary":"The finance analyst checks the source ledger."},
                  {"resultId":"%s","coverage":"UNKNOWN","evidenceIds":["%s"],"summary":"The exception owner is not known."},
                  {"resultId":"%s","coverage":"ASSUMPTION","evidenceIds":["%s"],"summary":"Assume the analyst owns routine checks."}],"nextAction":{
                  "type":"ASK_QUESTION","resultId":"%s","text":"What does the finance analyst check next?",
                  "responseMode":"CHOICE","options":[{"id":"ledger","label":"Source ledger"},{"id":"bank","label":"Bank statement"}]}}
                """.formatted(run.get("id"), run.get("expected_revision"), resultId, evidenceId,
                        resultId, evidenceId, resultId, evidenceId, resultId);
        for (int call = 0; call < 2; call++) {
            mockMvc.perform(post("/internal/runtime/turn").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(content().json("{\"event\":\"question_ready\"}"));
        }
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from questions where session_id=? and sequence=2", Integer.class, participant.sessionId())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from question_options where question_id=(select id from questions where session_id=? and sequence=2)", Integer.class, participant.sessionId())).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from result_outcomes where result_id=? and category='RULE'", Integer.class, resultId)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from result_outcome_evidence where evidence_id=?", Integer.class, evidenceId)).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from result_outcomes where result_id=? and coverage in ('UNKNOWN','ASSUMPTION')", Integer.class, resultId)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from unresolved_evidence where evidence_id=?", Integer.class, evidenceId)).isEqualTo(1);

        mockMvc.perform(post("/interview/structured-answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("optionId", "ledger").param("explanation", "That is the authoritative source."))
                .andExpect(status().is3xxRedirection());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForMap(
                        "select option_id,option_label,explanation from evidence where session_id=? and option_id is not null",
                        participant.sessionId()))
                .containsEntry("option_id", "ledger")
                .containsEntry("option_label", "Source ledger")
                .containsEntry("explanation", "That is the authoritative source.");

        var nextRun = jdbcTemplate.queryForMap(
                "select id,expected_revision from runtime_runs where session_id=? and state='PENDING'",
                participant.sessionId());
        var nextToken = "next-turn-scoped-test-token";
        jdbcTemplate.update("insert into runtime_turn_credentials values(?,?,now()+interval '5 minutes',null)",
                com.findworks.platform.Ids.sha(nextToken), nextRun.get("id"));
        mockMvc.perform(post("/internal/runtime/turn").header("Authorization", "Bearer " + nextToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"runId":"%s","expectedRevision":%s,"outcomes":[],"nextAction":{
                                  "type":"ASK_QUESTION","resultId":"%s","text":"Is the source ledger authoritative?",
                                  "responseMode":"YES_NO"}}
                                """.formatted(nextRun.get("id"), nextRun.get("expected_revision"), resultId)))
                .andExpect(status().isOk()).andExpect(content().json("{\"event\":\"question_ready\"}"));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForList("""
                        select option_id,label from question_options
                        where question_id=(select id from questions where session_id=? and sequence=3)
                        order by position
                        """, participant.sessionId()))
                .containsExactly(
                        Map.of("option_id", "yes", "label", "Yes"),
                        Map.of("option_id", "no", "label", "No"));
    }

    private void answerAndRun(ParticipantAccess participant, String answer) throws Exception {
        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", answer)).andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/test/runtime/run-once")).andExpect(status().isOk());
    }

    private tools.jackson.databind.JsonNode mcpCall(HarnessAccess access, String tool, String arguments) throws Exception {
        var response = mockMvc.perform(post("/mcp").header("Authorization", "Bearer " + access.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":20,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                                """.formatted(tool, arguments)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(response)
                .path("result").path("structuredContent");
    }

    private long distinctResultCount(tools.jackson.databind.JsonNode page) {
        var ids = new java.util.HashSet<String>();
        page.path("groups").forEach(group -> ids.add(group.path("investigation_item_id").asText()));
        return ids.size();
    }

    private ParticipantAccess beginInterview(HarnessAccess access, String missionId, String email) throws Exception {
        mockMvc.perform(post("/app/missions/{missionId}/invite", missionId)
                        .session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1").param("name", "Clare").param("email", email)
                        .param("delivery", "email"))
                .andExpect(status().is3xxRedirection());
        var emailBody = mockMvc.perform(get("/test/emails/latest").param("recipient", email))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var link = tools.jackson.databind.json.JsonMapper.builder().build().readTree(emailBody).path("link").asText();
        mockMvc.perform(get(link)).andExpect(status().isOk());
        var redemption = mockMvc.perform(post(link)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        var cookie = redemption.getResponse().getCookie("findworks_interview");
        var page = mockMvc.perform(get("/interview").cookie(cookie)).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) page.getRequest().getSession(false);
        mockMvc.perform(post("/interview/begin").session(session).cookie(cookie)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        var sessionId = jdbcTemplate.queryForObject("select id from interview_sessions where participant_id=(select id from participants where email=?)", UUID.class, email);
        return new ParticipantAccess(cookie, session, sessionId);
    }

    private void approveMission(HarnessAccess access, String missionId, int version) throws Exception {
        for (int checkpoint = 1; checkpoint <= 5; checkpoint++) {
            mockMvc.perform(post("/app/missions/{missionId}/review/checkpoints/{checkpoint}", missionId, checkpoint)
                            .session(access.session())
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                            .param("version", Integer.toString(version)))
                    .andExpect(status().is3xxRedirection());
        }
        mockMvc.perform(post("/app/missions/{missionId}/approve", missionId)
                        .session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", Integer.toString(version)))
                .andExpect(status().is3xxRedirection());
    }

    private tools.jackson.databind.JsonNode submitMission(HarnessAccess access, UUID submissionId) throws Exception {
        var call = """
                {"jsonrpc":"2.0","id":10,"method":"tools/call","params":{"name":"create_discovery_with_draft_mission","arguments":%s}}
                """.formatted(validMissionPayload(submissionId));
        var response = mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + access.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(call))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(response)
                .path("result").path("structuredContent");
    }

    private String validMissionPayload(UUID submissionId) {
        return """
                {
                  "contract_version":1,
                  "submission_id":"%s",
                  "discovery_title":"Ledgerling reconciliation discovery",
                  "discovery_objective":"Learn how accountants reconcile imported transactions.",
                  "mission":{
                    "objective":"Understand Ledgerling reconciliation",
                    "desired_outcome":"Know which mismatches need human judgement.",
                    "intended_interviewee_role":"Accountant",
                    "intended_interviewee_relevance":"Clare performs this work every month.",
                    "investigation_items":[{
                      "knowledge_gap":"Which mismatches require human judgement?",
                      "why_it_matters":"Automation must stop safely.",
                      "priority":"HIGH",
                      "required":true,
                      "relevant_context":"Ledgerling imports bank transactions.",
                      "sufficient_evidence":["Examples of at least two mismatch types"]
                    }],
                    "shared_context":["Ledgerling imports bank transactions."],
                    "boundaries":["Discuss reconciliation workflow only."],
                    "prohibited_topics":["Customer-identifying data"],
                    "terminology":[{"term":"Mismatch","meaning":"Imported data that does not reconcile automatically."}],
                    "opening_questions":["Walk me through the last mismatch you resolved."],
                    "completion_criteria":["Every required area has an explicit outcome."],
                    "expected_commitment_minutes":20,
                    "data_use_summary":"Reviewed findings and bounded source excerpts may be exported to the Investigator project; later deletion cannot recall those copies."
                  },
                  "origins":[
                    {"pointer":"/discovery_title","authority":"INVESTIGATOR_STATEMENT"},
                    {"pointer":"/discovery_objective","authority":"INVESTIGATOR_STATEMENT"},
                    {"pointer":"/mission/objective","authority":"INVESTIGATOR_STATEMENT"},
                    {"pointer":"/mission/desired_outcome","authority":"CONFIRMED_AGENT_PROPOSAL"},
                    {"pointer":"/mission/intended_interviewee_role","authority":"INVESTIGATOR_STATEMENT"},
                    {"pointer":"/mission/intended_interviewee_relevance","authority":"INVESTIGATOR_STATEMENT"},
                    {"pointer":"/mission/investigation_items/0","authority":"CONFIRMED_AGENT_PROPOSAL"},
                    {"pointer":"/mission/shared_context/0","authority":"INVESTIGATOR_STATEMENT"},
                    {"pointer":"/mission/boundaries/0","authority":"INVESTIGATOR_STATEMENT"},
                    {"pointer":"/mission/prohibited_topics/0","authority":"INVESTIGATOR_STATEMENT"},
                    {"pointer":"/mission/terminology/0","authority":"CONFIRMED_AGENT_PROPOSAL"},
                    {"pointer":"/mission/opening_questions/0","authority":"CONFIRMED_AGENT_PROPOSAL"},
                    {"pointer":"/mission/completion_criteria/0","authority":"CONFIRMED_AGENT_PROPOSAL"},
                    {"pointer":"/mission/expected_commitment_minutes","authority":"INVESTIGATOR_STATEMENT"},
                    {"pointer":"/mission/data_use_summary","authority":"INVESTIGATOR_STATEMENT"}
                  ],
                  "project_references":[{"kind":"repository_file","locator":"docs/domain.md","revision":"abc123"}],
                  "confirmations":{
                    "exact_payload_reviewed":true,
                    "intentional_sharing":true,
                    "no_recipient_or_private_context":true,
                    "material_agent_proposals_confirmed":true
                  }
                }
                """.formatted(submissionId);
    }

    private HarnessAccess authorizeHarness(String scope) throws Exception {
        var registration = mockMvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "client_name": "Acceptance harness",
                                  "redirect_uris": ["http://127.0.0.1:49152/callback"],
                                  "token_endpoint_auth_method": "none",
                                  "scope": "%s"
                                }
                                """.formatted(scope)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        var clientId = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(registration).path("client_id").asText();

        var login = mockMvc.perform(post("/signin")
                        .param("username", "gareth@example.com")
                        .param("password", "findworks")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        var verifier = "a-secure-verifier-with-more-than-forty-three-characters";
        var challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));

        mockMvc.perform(get("/oauth/authorize").session(session)
                        .param("response_type", "code")
                        .param("client_id", clientId)
                        .param("redirect_uri", "http://127.0.0.1:49152/callback")
                        .param("state", "acceptance-state")
                        .param("code_challenge", challenge)
                        .param("code_challenge_method", "S256")
                        .param("resource", "http://127.0.0.1:8080/mcp", "http://127.0.0.1:8080/mcp")
                        .param("scope", scope))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Acceptance harness")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("List your Discoveries")));

        var approval = mockMvc.perform(post("/oauth/authorize").session(session)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("client", clientId)
                        .param("redirect", "http://127.0.0.1:49152/callback")
                        .param("state", "acceptance-state")
                        .param("challenge", challenge)
                        .param("resource", "http://127.0.0.1:8080/mcp")
                        .param("scope", scope)
                        .param("decision", "approve"))
                .andExpect(status().is3xxRedirection())
                .andReturn().getResponse().getRedirectedUrl();
        var code = UriComponentsBuilder.fromUriString(approval).build().getQueryParams().getFirst("code");

        var tokenBody = mockMvc.perform(post("/oauth/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("client_id", clientId)
                        .param("redirect_uri", "http://127.0.0.1:49152/callback")
                        .param("code_verifier", verifier))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var accessToken = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(tokenBody).path("access_token").asText();
        var refreshToken = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(tokenBody).path("refresh_token").asText();
        return new HarnessAccess(accessToken, refreshToken, session, clientId, code, verifier);
    }

    private record HarnessAccess(String token, String refreshToken, MockHttpSession session, String clientId, String code, String verifier) {}
    private record ParticipantAccess(jakarta.servlet.http.Cookie cookie, MockHttpSession session, UUID sessionId) {}
}
