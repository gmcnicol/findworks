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
import java.sql.Connection;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.findworks.web.DeletionService;
import com.findworks.web.RuntimeTurnContext;
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
import org.springframework.transaction.annotation.Propagation;
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

    @Autowired
    RuntimeTurnContext runtimeTurnContext;

    @Autowired
    DeletionService deletionService;

    @Autowired
    DataSource dataSource;

    @Test
    void browser_errors_have_a_recoverable_findworks_page_instead_of_the_framework_fallback() throws Exception {
        mockMvc.perform(get("/error").requestAttr(jakarta.servlet.RequestDispatcher.ERROR_STATUS_CODE, 409))
                .andExpect(status().isConflict())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("That action is already out of date")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Return to saved work")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Whitelabel Error Page"))));
    }

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
    void owned_records_remain_visible_across_investigator_web_and_harness_paths_regardless_of_status() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var discoveryId = submitted.path("discovery_id").asText();
        var missionId = submitted.path("mission_id").asText();
        jdbcTemplate.update("update discoveries set state='UNAVAILABLE' where id=?", UUID.fromString(discoveryId));

        mockMvc.perform(get("/app").session(access.session())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Understand Ledgerling reconciliation")));
        mockMvc.perform(get("/app/discoveries").session(access.session())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(discoveryId)));
        mockMvc.perform(get("/app/discoveries/{id}", discoveryId).session(access.session())).andExpect(status().isOk());
        mockMvc.perform(get("/app/missions").session(access.session())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(missionId)));
        mockMvc.perform(get("/app/missions/{id}/review", missionId).session(access.session())).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(mcpCall(access, "list_discoveries", "{\"contract_version\":1}").toString())
                .contains(discoveryId);
    }

    @Test
    void returning_investigator_resumes_first_unreviewed_checkpoint_then_approval() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var mission = UUID.fromString(submitted.path("mission_id").asText());
        var discovery = submitted.path("discovery_id").asText();
        jdbcTemplate.update("insert into mission_review values(?,1,1)", mission);
        jdbcTemplate.update("insert into mission_review values(?,1,3)", mission);
        mockMvc.perform(get("/app/missions").session(access.session())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/review#interviewee-heading")));
        mockMvc.perform(get("/app/discoveries/" + discovery).session(access.session())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/review#interviewee-heading")));
        for (int checkpoint : new int[] {2,4,5}) jdbcTemplate.update("insert into mission_review values(?,1,?)", mission, checkpoint);
        mockMvc.perform(get("/app").session(access.session())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/review#approval-decision")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ready for approval")));
    }

    @Test
    void paused_interview_reopens_in_new_http_session_and_server_expiry_denies_the_old_grant() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var mission = submitted.path("mission_id").asText();
        approveMission(access, mission, 1);
        var participant = beginInterview(access, mission, "continuity@example.com");
        answerAndRun(participant, "A missing reference required a manual correction.");
        mockMvc.perform(post("/interview/pause").session(participant.session()).cookie(participant.cookie())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        var reopened = mockMvc.perform(get("/interview").cookie(participant.cookie())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your place is saved"))).andReturn();
        mockMvc.perform(post("/interview/resume").session((MockHttpSession) reopened.getRequest().getSession())
                .cookie(participant.cookie()).with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/app/interviews/" + participant.sessionId()).session(access.session()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Interview in progress")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Accepted answers")));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("select count(*) from evidence where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);
        jdbcTemplate.update("update access_grants set expires_at=now()-interval '1 second' where session_id=?", participant.sessionId());
        mockMvc.perform(get("/interview").cookie(participant.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(get("/app/discoveries/" + submitted.path("discovery_id").asText()).session(access.session()))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Interview access needs reissue")));
    }

    @Test
    void reissue_cannot_change_a_started_interviewees_evidence_attribution() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var mission = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, mission, 1);
        var participant = beginInterview(access, mission, "original-owner@example.com");
        mockMvc.perform(post("/app/missions/" + mission + "/invite").session(access.session())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("version", "1").param("name", "Another person").param("email", "different-owner@example.com"))
                .andExpect(status().isUnprocessableEntity());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("select p.email from participants p join interview_sessions s on s.participant_id=p.id where s.id=?", String.class, participant.sessionId()))
                .isEqualTo("original-owner@example.com");
        mockMvc.perform(get("/interview").cookie(participant.cookie())).andExpect(status().isOk());
    }

    @Test
    void new_version_reissue_before_begin_uses_new_approved_version_in_the_same_session() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var mission = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, mission, 1);
        mockMvc.perform(post("/app/missions/" + mission + "/invite").session(access.session())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("version", "1").param("name", "Clare").param("email", "version-reissue@example.com").param("delivery", "email"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/app/missions/" + mission + "/edit").session(access.session())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("version", "1").param("field", "objective").param("value", "Explore the revised objective"))
                .andExpect(status().is3xxRedirection());
        approveMission(access, mission, 2);
        mockMvc.perform(post("/app/missions/" + mission + "/invite").session(access.session())
                .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .param("version", "2").param("name", "Clare").param("email", "version-reissue@example.com").param("delivery", "email"))
                .andExpect(status().is3xxRedirection());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("select count(*) from interview_sessions where mission_id=?", Integer.class, UUID.fromString(mission))).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("select mission_version from interview_sessions where mission_id=?", Integer.class, UUID.fromString(mission))).isEqualTo(2);
    }

    @Test
    void meaningful_activity_updates_recency_but_reads_and_noop_updates_do_not() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var mission = UUID.fromString(submitMission(access, UUID.randomUUID()).path("mission_id").asText());
        jdbcTemplate.update("update missions set last_activity_at=now()-interval '1 day' where id=?", mission);
        var before = jdbcTemplate.queryForObject("select last_activity_at from missions where id=?", java.time.OffsetDateTime.class, mission);
        mockMvc.perform(get("/app").session(access.session())).andExpect(status().isOk());
        jdbcTemplate.update("update mission_versions set objective=objective where mission_id=?", mission);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("select last_activity_at from missions where id=?", java.time.OffsetDateTime.class, mission)).isEqualTo(before);
        jdbcTemplate.update("insert into mission_review values(?,1,1)", mission);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("select last_activity_at from missions where id=?", java.time.OffsetDateTime.class, mission)).isAfter(before);
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
                .andExpect(jsonPath("$.deferred_runtime_retries").value(0))
                .andExpect(jsonPath("$.runtime_circuit_open").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"pending_email\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"failed_runtime_runs\"")))
                .andExpect(jsonPath("$.deletion_enabled").value(false))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("pending_deletions"))))
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
    void oauth_refresh_token_survives_concurrent_clients_and_revocation_ends_the_grant() throws Exception {
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
        org.assertj.core.api.Assertions.assertThat(refreshToken).isEqualTo(access.refreshToken());

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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refresh_token").value(access.refreshToken()));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select expires_at='infinity'::timestamptz from oauth_refresh_tokens where hash=?",
                Boolean.class, com.findworks.platform.Ids.sha(access.refreshToken()))).isTrue();

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
    void mission_tools_do_not_expose_duration_or_commitment() throws Exception {
        var access = authorizeHarness("findworks:read");
        mockMvc.perform(post("/mcp")
                        .header("Authorization", "Bearer " + access.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","id":25,"method":"tools/list","params":{}}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("expected_commitment"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("expected_minutes"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("time limit"))));
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
                .andExpect(status().isUnprocessableContent());

        for (int checkpoint = 1; checkpoint <= 5; checkpoint++) {
            mockMvc.perform(post("/app/missions/{missionId}/review/checkpoints/{checkpoint}", missionId, checkpoint)
                            .session(access.session())
                            .header("Datastar-Request", "true")
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                            .param("version", "1"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Datastar-Selector", "#mission-review-state"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("Reviewed ·")));
        }
        mockMvc.perform(post("/app/missions/{missionId}/review/checkpoints/1", missionId)
                        .session(access.session())
                        .header("Datastar-Request", "true")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("5 of 5 checkpoints reviewed")));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from mission_review where mission_id=? and version=1",
                Integer.class, UUID.fromString(missionId))).isEqualTo(5);

        mockMvc.perform(post("/app/missions/{missionId}/approve", missionId)
                        .session(access.session())
                        .header("Datastar-Request", "true")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("Datastar-Selector", "#mission-review-state"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Approved · Version 1")));
        mockMvc.perform(post("/app/missions/{missionId}/approve", missionId)
                        .session(access.session())
                        .header("Datastar-Request", "true")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Approved · Version 1")));
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
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Retention"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("days after findings review"))))
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
        var sessionId = jdbcTemplate.queryForObject("select session_id from invitations where recipient_email='clare@example.com'", UUID.class);
        jdbcTemplate.update("update interview_sessions set started_at=now()-interval '3 hours' where id=?", sessionId);
        mockMvc.perform(get("/interview").session(participantSession).cookie(interviewCookie))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Walk me through the last mismatch you resolved.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Your answer")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Nearly at the expected time"))));

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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-init=\"@get(&#39;/interview/events&#39;)\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("http-equiv=\"refresh\""))))
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

        mockMvc.perform(get("/interview/state").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(header().string("Datastar-Selector", "#interview-shell"))
                .andExpect(header().string("Datastar-Mode", "outer"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("id=\"interview-shell\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("What does the finance analyst check in the source ledger?")));

        for (int refresh = 0; refresh < 2; refresh++) {
            mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("The finance analyst checks the source ledger")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("What does the finance analyst check in the source ledger?")))
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("http-equiv=\"refresh\""))));
        }
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from questions where session_id=? and active", Integer.class, participant.sessionId())).isEqualTo(1);
    }

    @Test
    void conversation_transcript_keeps_original_evidence_and_labels_an_appended_correction() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "transcript-correction@example.com");
        var questionId = jdbcTemplate.queryForObject(
                "select id from questions where session_id=? and active", UUID.class, participant.sessionId());

        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("questionId", questionId.toString())
                        .param("answer", "Every imported amount needs approval."))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/test/runtime/run-once").param("sessionId", participant.sessionId().toString()))
                .andExpect(status().isOk());
        var evidenceId = jdbcTemplate.queryForObject(
                "select id from evidence where session_id=? and source_type='ANSWER'", UUID.class, participant.sessionId());
        var revision = jdbcTemplate.queryForObject(
                "select revision from interview_sessions where id=?", Integer.class, participant.sessionId());

        mockMvc.perform(get("/interview").queryParam("revise", evidenceId.toString())
                        .session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-revision-form")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("data-answer-form"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<aside"))));

        mockMvc.perform(post("/interview/revise").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("evidenceId", evidenceId.toString())
                        .param("expectedRevision", revision.toString())
                        .param("answer", "Only unmatched imported amounts need approval."))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Conversation transcript")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Walk me through the last mismatch you resolved.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Every imported amount needs approval.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Only unmatched imported amounts need approval.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Correction · saved")));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from evidence where session_id=?", Integer.class, participant.sessionId())).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select revises from evidence where session_id=? and source_type='REVISION'", UUID.class, participant.sessionId()))
                .isEqualTo(evidenceId);

        mockMvc.perform(post("/interview/revise").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("evidenceId", evidenceId.toString())
                        .param("expectedRevision", revision.toString())
                        .param("answer", "A stale browser tried to add a different correction."))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "The conversation moved on before that correction was saved.")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-focus-key=\"revision-conflict\"")));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from evidence where session_id=?", Integer.class, participant.sessionId())).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrent_identical_legacy_revisions_append_one_evidence_and_one_runtime_work_item() throws Exception {
        var email = "concurrent-revision-" + UUID.randomUUID() + "@example.com";
        var fixtureBody = mockMvc.perform(post("/test/fixtures/approved-mission").param("email", email))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var fixture = tools.jackson.databind.json.JsonMapper.builder().build().readTree(fixtureBody);
        var discoveryId = UUID.fromString(fixture.path("discovery_id").asText());
        var sessionId = UUID.fromString(fixture.path("session_id").asText());
        var invitationUrl = fixture.path("invitation_url").asText();
        var redemption = mockMvc.perform(post(invitationUrl)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection()).andReturn();
        var cookie = redemption.getResponse().getCookie("findworks_interview");

        try {
            mockMvc.perform(post("/interview/begin").cookie(cookie)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().is3xxRedirection());
            mockMvc.perform(post("/interview/answer").cookie(cookie)
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                            .param("answer", "Every imported amount needs approval."))
                    .andExpect(status().is3xxRedirection());
            mockMvc.perform(post("/test/runtime/run-once").param("sessionId", sessionId.toString()))
                    .andExpect(status().isOk());
            var evidenceId = jdbcTemplate.queryForObject(
                    "select id from evidence where session_id=? and source_type='ANSWER'", UUID.class, sessionId);
            var correction = "Only unmatched imported amounts need approval.";
            var start = new CyclicBarrier(3);

            try (Connection blocker = dataSource.getConnection();
                 var lock = blocker.prepareStatement("select id from interview_sessions where id=? for update");
                 var executor = Executors.newFixedThreadPool(2)) {
                blocker.setAutoCommit(false);
                lock.setObject(1, sessionId);
                lock.executeQuery().close();

                var first = executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/interview/revise").cookie(cookie)
                                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                                    .param("evidenceId", evidenceId.toString()).param("answer", correction))
                            .andReturn().getResponse().getStatus();
                });
                var second = executor.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/interview/revise").cookie(cookie)
                                    .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                                    .param("evidenceId", evidenceId.toString()).param("answer", correction))
                            .andReturn().getResponse().getStatus();
                });
                start.await();
                var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (System.nanoTime() < deadline && jdbcTemplate.queryForObject("""
                        select count(*) from pg_stat_activity
                        where datname=current_database() and wait_event_type='Lock'
                          and query like '%interview_sessions%'
                        """, Integer.class) < 2) Thread.sleep(10);
                blocker.commit();

                org.assertj.core.api.Assertions.assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(302);
                org.assertj.core.api.Assertions.assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(302);
            }

            org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from evidence where session_id=? and source_type='REVISION'", Integer.class, sessionId))
                    .isEqualTo(1);
            org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                    select count(*) from runtime_runs r join evidence e on e.id=r.evidence_id
                    where r.session_id=? and e.source_type='REVISION'
                    """, Integer.class, sessionId)).isEqualTo(1);
        } finally {
            jdbcTemplate.update("delete from discoveries where id=?", discoveryId);
        }
    }

    @Test
    void authorised_unavailable_interview_retains_transcript_but_revoked_access_leaks_nothing() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "unavailable-transcript@example.com");
        var accepted = "Priya compares the cashbook with the source ledger.";
        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", accepted))
                .andExpect(status().is3xxRedirection());
        jdbcTemplate.update("update interview_sessions set state='UNAVAILABLE' where id=?", participant.sessionId());

        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Conversation transcript")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(accepted)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-clear-session-drafts")));

        jdbcTemplate.update("update access_grants set revoked_at=now() where session_id=?", participant.sessionId());
        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(accepted))));
    }

    @Test
    void interview_progress_copy_follows_the_authoritative_investigation_result_state() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "progress-state@example.com");

        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1 key area to explore")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("0 of 1 key areas covered"))));

        jdbcTemplate.update("update investigation_results set coverage='EXPLORING' where session_id=?", participant.sessionId());
        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Exploring 1 key area")));

        jdbcTemplate.update("update investigation_results set coverage='UNKNOWN' where session_id=?", participant.sessionId());
        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("All key areas have an outcome")));
    }

    @Test
    void unknown_on_one_required_item_continues_to_the_remaining_required_item() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "continue-after-unknown@example.com");
        var secondItem = UUID.randomUUID();
        jdbcTemplate.update("insert into investigation_items values(?,?,1,1,'Who approves exceptions?','Ownership must be explicit.','HIGH',true,'An exception exists.','The owner or an explicit unknown')",
                secondItem, UUID.fromString(missionId));
        jdbcTemplate.update("insert into investigation_results values(?,(select organization_id from interview_sessions where id=?),?,?, 'UNADDRESSED')",
                UUID.randomUUID(), participant.sessionId(), participant.sessionId(), secondItem);

        mockMvc.perform(post("/interview/do-not-know").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Preparing your next question")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Confirm and finish"))));
    }

    @Test
    void replayed_answer_is_create_once_and_cannot_answer_a_later_question() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "replayed-answer@example.com");
        var questionId = jdbcTemplate.queryForObject(
                "select id from questions where session_id=? and active", UUID.class, participant.sessionId());

        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                            .param("questionId", questionId.toString()).param("answer", "The finance analyst checks the source ledger."))
                    .andExpect(status().is3xxRedirection());
        }
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from evidence where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from runtime_runs where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);

        mockMvc.perform(post("/test/runtime/run-once").param("sessionId", participant.sessionId().toString()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("questionId", questionId.toString()).param("answer", "Delayed duplicate must not answer the next question."))
                .andExpect(status().is3xxRedirection());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from evidence where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);
        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("What does the finance analyst check in the source ledger?")));
    }

    @Test
    void interview_lifecycle_commands_are_idempotent() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "idempotent-lifecycle@example.com");

        mockMvc.perform(post("/interview/begin").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(post("/interview/pause").session(participant.session()).cookie(participant.cookie())
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().is3xxRedirection());
        }
        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(post("/interview/resume").session(participant.session()).cookie(participant.cookie())
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().is3xxRedirection());
        }
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from questions where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from investigation_results where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);

        var questionId = jdbcTemplate.queryForObject(
                "select id from questions where session_id=? and active", UUID.class, participant.sessionId());
        mockMvc.perform(post("/interview/do-not-know").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("questionId", questionId.toString()))
                .andExpect(status().is3xxRedirection());
        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(post("/interview/confirm-completion").session(participant.session()).cookie(participant.cookie())
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().is3xxRedirection());
        }
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select state from interview_sessions where id=?", String.class, participant.sessionId())).isEqualTo("COMPLETED");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from evidence where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);
    }

    @Test
    void persisted_premature_confirmation_recovers_without_a_conflict() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "recover-premature-confirmation@example.com");
        var secondItem = UUID.randomUUID();
        jdbcTemplate.update("insert into investigation_items values(?,?,1,1,'Who approves exceptions?','Ownership must be explicit.','HIGH',true,'An exception exists.','The owner or an explicit unknown')",
                secondItem, UUID.fromString(missionId));
        jdbcTemplate.update("insert into investigation_results values(?,(select organization_id from interview_sessions where id=?),?,?, 'UNADDRESSED')",
                UUID.randomUUID(), participant.sessionId(), participant.sessionId(), secondItem);
        mockMvc.perform(post("/interview/do-not-know").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        jdbcTemplate.update("delete from runtime_runs where session_id=?", participant.sessionId());
        jdbcTemplate.update("update interview_sessions set state='AWAITING_CONFIRMATION' where id=?", participant.sessionId());

        mockMvc.perform(post("/interview/confirm-completion").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Preparing your next question")));
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
    void investigator_can_explicitly_accept_findings_with_required_areas_still_open() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var missionId = submitted.path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "open-findings@example.com");
        jdbcTemplate.update("update questions set active=false where session_id=?", participant.sessionId());
        jdbcTemplate.update("update interview_sessions set state='COMPLETED',completed_at=now() where id=?", participant.sessionId());

        mockMvc.perform(post("/test/extraction/run-once")).andExpect(status().isOk());
        mockMvc.perform(get("/app/findings/{sessionId}", participant.sessionId()).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("1 required area remains open")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Open · acceptance allowed")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Accept with 1 required area still open")))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("id=\"accept-findings-package\" disabled"))));

        mockMvc.perform(post("/app/findings/{sessionId}/accept", participant.sessionId()).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(post("/app/findings/{sessionId}/accept", participant.sessionId()).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1").param("acceptOpenAreas", "true").param("notes", "Useful findings are ready."))
                .andExpect(status().is3xxRedirection());

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForMap("""
                        select pv.state,pv.notes from findings_packages fp
                        join package_versions pv on pv.package_id=fp.id and pv.version=fp.current_version
                        where fp.session_id=?
                        """, participant.sessionId()))
                .containsEntry("state", "ACCEPTED");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                        select notes from findings_packages fp join package_versions pv
                        on pv.package_id=fp.id and pv.version=fp.current_version where fp.session_id=?
                        """, String.class, participant.sessionId()))
                .contains("1 required Investigation Item still open", "Useful findings are ready.");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                        select count(*) from audit_events where action='FINDINGS_ACCEPTED_WITH_OPEN_AREAS'
                          and resource_id=(select id from findings_packages where session_id=?)
                        """, Integer.class, participant.sessionId())).isEqualTo(1);
    }

    @Test
    void finishing_an_interview_completes_it_and_extracts_saved_findings_for_review() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "finished-findings@example.com");

        mockMvc.perform(post("/interview/complete").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForMap(
                        "select state,extraction_state from interview_sessions where id=?", participant.sessionId()))
                .containsEntry("state", "COMPLETED")
                .containsEntry("extraction_state", "PENDING");

        mockMvc.perform(post("/test/extraction/run-once"))
                .andExpect(status().isOk()).andExpect(content().json("{\"event\":\"findings_ready\"}"));
        mockMvc.perform(get("/app/findings/{sessionId}", participant.sessionId()).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ready for review")));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from findings_packages where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);
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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Open provenance")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Question asked")))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(review)
                .doesNotContain("<h2 id=\"transcript-heading\">", "findings@example.com")
                .contains("View full transcript");
        var transcript = mockMvc.perform(get("/app/findings/{sessionId}", participant.sessionId())
                        .session(access.session()).param("view", "transcript"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Interview transcript")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("A mismatch needs Gareth&#39;s approval")))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(transcript)
                .doesNotContain("finding-actions", "Accept exact package")
                .contains("Review findings");

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
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select retention_due_at from discoveries where id=?", java.time.OffsetDateTime.class,
                UUID.fromString(submitted.path("discovery_id").asText()))).isNull();

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
        var unresolvedQuestion = jdbcTemplate.queryForObject(
                "select q.text from questions q join evidence e on e.question_id=q.id where e.session_id=? limit 1",
                String.class, participant.sessionId());
        mockMvc.perform(get("/app/findings/{sessionId}", participant.sessionId()).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Question asked")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(unresolvedQuestion)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Answer given")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("I don&#39;t know")));

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
                .containsEntry("notes", "Follow up with the reconciliation owner.")
                .containsEntry("retention_due_at", null);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from publications where discovery_id=?", Integer.class, discoveryId)).isZero();
        mockMvc.perform(post("/app/findings/{sessionId}/reject", participant.sessionId()).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("version", "1").param("notes", "Try again."))
                .andExpect(status().is3xxRedirection());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select notes from package_versions where package_id=(select id from findings_packages where session_id=?) and version=1",
                String.class, participant.sessionId())).isEqualTo("Follow up with the reconciliation owner.");
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
        mockMvc.perform(get("/app/interviews/{sessionId}", first.sessionId()).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Review saved transcript")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "/app/findings/" + first.sessionId() + "?view=transcript#transcript-heading")));
        mockMvc.perform(get("/app/findings/{sessionId}", first.sessionId()).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Interview transcript")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Interview in progress · transcript saved")));

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
                "select state from interview_sessions where id=?", String.class, first.sessionId())).isEqualTo("COMPLETED");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                select count(*) from pg_trigger where tgname='interview_sessions_completed_is_terminal'
                """, Integer.class)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select state from runtime_runs where session_id=?", String.class, first.sessionId()))
                .isEqualTo("SUPERSEDED");
        mockMvc.perform(post("/test/runtime/run-once").param("sessionId", first.sessionId().toString()))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select state from interview_sessions where id=?", String.class, first.sessionId())).isEqualTo("COMPLETED");
        mockMvc.perform(get("/app/findings").session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/app/findings/" + first.sessionId())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Extraction pending")));
        mockMvc.perform(get("/app/interviews/{sessionId}", first.sessionId()).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Continue with findings")));
        mockMvc.perform(get("/app/findings/{sessionId}", first.sessionId()).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Interview transcript")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("This accepted answer must survive access recovery.")));
        mockMvc.perform(post("/test/extraction/run-once"))
                .andExpect(status().isOk()).andExpect(content().json("{\"event\":\"findings_ready\"}"));
        mockMvc.perform(get("/app/findings/{sessionId}", first.sessionId()).session(access.session()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Ready for review")));

        mockMvc.perform(get("/interview").session(first.session()).cookie(first.cookie())).andExpect(status().isForbidden());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from interview_sessions where id=?", Integer.class, first.sessionId())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from participants where email='recover@example.com'", Integer.class)).isEqualTo(1);
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

        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(post("/interview/retry").session(participant.session()).cookie(participant.cookie())
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().is3xxRedirection());
        }
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from runtime_runs where session_id=? and state='PENDING'", Integer.class, participant.sessionId())).isEqualTo(1);
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
        for (int replay = 0; replay < 2; replay++) {
            mockMvc.perform(post("/app/findings/{sessionId}/retry", participant.sessionId()).session(access.session())
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                    .andExpect(status().is3xxRedirection());
        }
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
    void v1_deletion_and_retention_endpoints_fail_closed_without_mutation() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var discoveryId = UUID.fromString(submitted.path("discovery_id").asText());
        var missionId = submitted.path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "preserved@example.com");
        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "This Evidence must remain available."))
                .andExpect(status().is3xxRedirection());

        mockMvc.perform(post("/app/discoveries/{discoveryId}/delete", discoveryId).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/app/interviews/{sessionId}/delete", participant.sessionId()).session(access.session())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/operator/discoveries/{discoveryId}/delete", discoveryId)
                        .header("X-Operator-Token", "test-operator-token"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/operator/sessions/{sessionId}/delete", participant.sessionId())
                        .header("X-Operator-Token", "test-operator-token"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/operator/retention/extend").header("X-Operator-Token", "test-operator-token")
                        .param("discoveryId", discoveryId.toString()).param("days", "30"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/operator/retention/run-once").header("X-Operator-Token", "test-operator-token"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/operator/purge/run-once").header("X-Operator-Token", "test-operator-token"))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForMap(
                "select state,deleted_at,retention_due_at from discoveries where id=?", discoveryId))
                .containsEntry("state", "ACTIVE").containsEntry("deleted_at", null).containsEntry("retention_due_at", null);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select deleted_at from interview_sessions where id=?", java.time.OffsetDateTime.class, participant.sessionId())).isNull();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from evidence where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from deletion_ledger where resource_id in (?,?)", Integer.class, discoveryId, participant.sessionId())).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from retention_warnings where discovery_id=?", Integer.class, discoveryId)).isZero();
        mockMvc.perform(get("/interview").session(participant.session()).cookie(participant.cookie())).andExpect(status().isOk());
    }

    @Test
    void direct_lifecycle_invocation_cannot_schedule_mark_or_purge_data() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var submitted = submitMission(access, UUID.randomUUID());
        var discoveryId = UUID.fromString(submitted.path("discovery_id").asText());
        var missionId = submitted.path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "direct-lifecycle@example.com");
        jdbcTemplate.update("update discoveries set retention_due_at=now()-interval '1 day',last_activity_at=now()-interval '181 days' where id=?", discoveryId);
        var ledgerId = UUID.randomUUID();
        jdbcTemplate.update("insert into deletion_ledger(id,organization_id,kind,resource_id,purge_due_at) values(?,'11111111-1111-1111-1111-111111111111','SESSION',?,now()-interval '1 day')",
                ledgerId, participant.sessionId());
        var auditId = UUID.randomUUID();
        jdbcTemplate.update("insert into audit_events(id,actor_kind,action,resource_kind,resource_id,outcome,correlation_id,expires_at) values(?,'OPERATOR','LEGACY_EVENT','OPERATIONS',?,'SUCCEEDED',?,now()-interval '1 day')",
                auditId, participant.sessionId(), UUID.randomUUID());

        deletionService.processRetention();
        deletionService.processPurge();

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select state from discoveries where id=?", String.class, discoveryId)).isEqualTo("ACTIVE");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select deleted_at from interview_sessions where id=?", java.time.OffsetDateTime.class, participant.sessionId())).isNull();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from interview_sessions where id=?", Integer.class, participant.sessionId())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select purged_at from deletion_ledger where id=?", java.time.OffsetDateTime.class, ledgerId)).isNull();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from retention_warnings where discovery_id=?", Integer.class, discoveryId)).isZero();
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where id=?", Integer.class, auditId)).isEqualTo(1);
    }

    @Test
    void runtime_cannot_close_an_unasked_required_area_as_unresolved_from_unrelated_evidence() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "premature-completion@example.com");
        var secondItem = UUID.randomUUID();
        jdbcTemplate.update("insert into investigation_items values(?,?,1,1,'Who approves exceptions?','Ownership must be explicit.','HIGH',true,'An exception exists.','The owner or an explicit unknown')",
                secondItem, UUID.fromString(missionId));
        var secondResult = UUID.randomUUID();
        jdbcTemplate.update("insert into investigation_results values(?,(select organization_id from interview_sessions where id=?),?,?, 'UNADDRESSED')",
                secondResult, participant.sessionId(), participant.sessionId(), secondItem);

        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "The finance analyst checks the source ledger."))
                .andExpect(status().is3xxRedirection());
        var run = jdbcTemplate.queryForMap("select id,expected_revision from runtime_runs where session_id=?", participant.sessionId());
        var firstResult = jdbcTemplate.queryForObject(
                "select id from investigation_results where session_id=? and item_id<>?", UUID.class,
                participant.sessionId(), secondItem);
        var evidenceId = jdbcTemplate.queryForObject("select id from evidence where session_id=?", UUID.class, participant.sessionId());
        var token = "premature-completion-turn-token";
        jdbcTemplate.update("insert into runtime_turn_credentials values(?,?,now()+interval '5 minutes',null)",
                com.findworks.platform.Ids.sha(token), run.get("id"));

        mockMvc.perform(post("/internal/runtime/turn").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"runId":"%s","expectedRevision":%s,"outcomes":[
                                  {"resultId":"%s","coverage":"SUPPORTED","category":"FACT","evidenceIds":["%s"],"summary":"The analyst checks the ledger."},
                                  {"resultId":"%s","coverage":"UNKNOWN","evidenceIds":["%s"],"summary":"The approver is unknown."}],
                                  "nextAction":{"type":"PROPOSE_COMPLETION"}}
                                """.formatted(run.get("id"), run.get("expected_revision"), firstResult, evidenceId,
                                secondResult, evidenceId)))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"unsubstantiated_unresolved_outcome\"}"));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select state from interview_sessions where id=?", String.class, participant.sessionId())).isEqualTo("ACTIVE");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from result_outcomes where result_id in (?,?)", Integer.class, firstResult, secondResult)).isZero();
    }

    @Test
    void runtime_keeps_drilling_the_current_investigation_thread_until_it_has_an_outcome() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "one-thread@example.com");
        var secondItem = UUID.randomUUID();
        jdbcTemplate.update("insert into investigation_items values(?,?,1,1,'Who approves exceptions?','Ownership must be explicit.','HIGH',true,'An exception exists.','The owner or an explicit unknown')",
                secondItem, UUID.fromString(missionId));
        var secondResult = UUID.randomUUID();
        jdbcTemplate.update("insert into investigation_results values(?,(select organization_id from interview_sessions where id=?),?,?, 'UNADDRESSED')",
                secondResult, participant.sessionId(), participant.sessionId(), secondItem);

        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "We call it the day-end check, and Priya compares each mismatch with the cashbook."))
                .andExpect(status().is3xxRedirection());
        var run = jdbcTemplate.queryForMap("select id,expected_revision from runtime_runs where session_id=?", participant.sessionId());
        var firstResult = jdbcTemplate.queryForObject("""
                select r.id from questions q join investigation_results r on r.item_id=q.item_id and r.session_id=q.session_id
                where q.session_id=? and q.sequence=1
                """, UUID.class, participant.sessionId());
        var context = tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(
                runtimeTurnContext.project((UUID) run.get("id"), participant.sessionId(), 1,
                        ((Number) run.get("expected_revision")).intValue()));
        org.assertj.core.api.Assertions.assertThat(context.at("/conversation_state/current_thread_result_id").asText())
                .isEqualTo(firstResult.toString());
        org.assertj.core.api.Assertions.assertThat(context.at("/conversation_state/latest_interviewee_wording").asText())
                .isEqualTo("We call it the day-end check, and Priya compares each mismatch with the cashbook.");
        org.assertj.core.api.Assertions.assertThat(context.at("/questions_and_evidence/0/result_id").asText())
                .isEqualTo(firstResult.toString());
        var token = "one-thread-turn-token";
        jdbcTemplate.update("insert into runtime_turn_credentials values(?,?,now()+interval '5 minutes',null)",
                com.findworks.platform.Ids.sha(token), run.get("id"));

        mockMvc.perform(post("/internal/runtime/turn").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"runId":"%s","expectedRevision":%s,"outcomes":[],
                                  "nextAction":{"type":"ASK_QUESTION","resultId":"%s","text":"Who approves exceptions?"}}
                                """.formatted(run.get("id"), run.get("expected_revision"), secondResult)))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"current_thread_still_open\"}"));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from questions where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);
    }

    @Test
    void runtime_cannot_question_a_result_after_the_interviewee_marks_it_unknown() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "unknown-ends-thread@example.com");
        var nextItem = UUID.randomUUID();
        jdbcTemplate.update("insert into investigation_items values(?,?,1,1,'What happens next?','The next step matters.','HIGH',true,'The first area may be unknown.','A grounded answer or explicit unknown')",
                nextItem, UUID.fromString(missionId));
        jdbcTemplate.update("insert into investigation_results values(?,(select organization_id from interview_sessions where id=?),?,?, 'UNADDRESSED')",
                UUID.randomUUID(), participant.sessionId(), participant.sessionId(), nextItem);

        mockMvc.perform(post("/interview/do-not-know").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        var terminalResult = jdbcTemplate.queryForObject("""
                select r.id from questions q join investigation_results r on r.item_id=q.item_id and r.session_id=q.session_id
                where q.session_id=? and q.sequence=1
                """, UUID.class, participant.sessionId());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select coverage from investigation_results where id=?", String.class, terminalResult)).isEqualTo("UNKNOWN");
        var run = jdbcTemplate.queryForMap(
                "select id,expected_revision from runtime_runs where session_id=? and state='PENDING'", participant.sessionId());
        var token = "terminal-unknown-turn-token";
        jdbcTemplate.update("insert into runtime_turn_credentials values(?,?,now()+interval '5 minutes',null)",
                com.findworks.platform.Ids.sha(token), run.get("id"));

        mockMvc.perform(post("/internal/runtime/turn").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"runId":"%s","expectedRevision":%s,"outcomes":[],
                                  "nextAction":{"type":"ASK_QUESTION","resultId":"%s","text":"Who else might know?"}}
                                """.formatted(run.get("id"), run.get("expected_revision"), terminalResult)))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"code\":\"terminal_result_cannot_be_queried\"}"));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from questions where session_id=?", Integer.class, participant.sessionId())).isEqualTo(1);
    }

    @Test
    void repeated_outcome_proposal_is_not_duplicated_in_findings() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "deduplicated-outcomes@example.com");
        var nextItem = UUID.randomUUID();
        jdbcTemplate.update("insert into investigation_items values(?,?,1,1,'What happens after the check?','The next step matters.','HIGH',true,'The ledger check is complete.','The next recorded step')",
                nextItem, UUID.fromString(missionId));
        var nextResult = UUID.randomUUID();
        jdbcTemplate.update("insert into investigation_results values(?,(select organization_id from interview_sessions where id=?),?,?, 'UNADDRESSED')",
                nextResult, participant.sessionId(), participant.sessionId(), nextItem);

        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "The finance analyst checks the source ledger."))
                .andExpect(status().is3xxRedirection());
        var resultId = jdbcTemplate.queryForObject("""
                select r.id from questions q join investigation_results r on r.item_id=q.item_id and r.session_id=q.session_id
                where q.session_id=? and q.sequence=1
                """, UUID.class, participant.sessionId());
        var evidenceId = jdbcTemplate.queryForObject(
                "select id from evidence where session_id=?", UUID.class, participant.sessionId());
        var firstRun = jdbcTemplate.queryForMap(
                "select id,expected_revision from runtime_runs where session_id=? and state='PENDING'", participant.sessionId());
        var firstToken = "first-deduplicated-turn-token";
        jdbcTemplate.update("insert into runtime_turn_credentials values(?,?,now()+interval '5 minutes',null)",
                com.findworks.platform.Ids.sha(firstToken), firstRun.get("id"));
        var claim = "The finance analyst checks the source ledger.";
        mockMvc.perform(post("/internal/runtime/turn").header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"runId":"%s","expectedRevision":%s,"outcomes":[
                                  {"resultId":"%s","coverage":"SUPPORTED","category":"RULE","evidenceIds":["%s"],"summary":"%s"}],
                                  "nextAction":{"type":"ASK_QUESTION","resultId":"%s","text":"What happens next?"}}
                                """.formatted(firstRun.get("id"), firstRun.get("expected_revision"), resultId,
                                evidenceId, claim, nextResult)))
                .andExpect(status().isOk()).andExpect(content().json("{\"event\":\"question_ready\"}"));

        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "Then the analyst records the matched total."))
                .andExpect(status().is3xxRedirection());
        var secondEvidenceId = jdbcTemplate.queryForObject(
                "select id from evidence where session_id=? and exact_text=?", UUID.class,
                participant.sessionId(), "Then the analyst records the matched total.");
        var secondRun = jdbcTemplate.queryForMap(
                "select id,expected_revision from runtime_runs where session_id=? and state='PENDING'", participant.sessionId());
        var projectedContext = tools.jackson.databind.json.JsonMapper.builder().build().valueToTree(
                runtimeTurnContext.project((UUID) secondRun.get("id"), participant.sessionId(), 1,
                        ((Number) secondRun.get("expected_revision")).intValue()));
        org.assertj.core.api.Assertions.assertThat(
                projectedContext.at("/investigation_results/0/current_outcomes").size()).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(
                projectedContext.at("/investigation_results/0/current_outcomes/0/summary").asText()).isEqualTo(claim);

        var secondToken = "second-deduplicated-turn-token";
        jdbcTemplate.update("insert into runtime_turn_credentials values(?,?,now()+interval '5 minutes',null)",
                com.findworks.platform.Ids.sha(secondToken), secondRun.get("id"));
        mockMvc.perform(post("/internal/runtime/turn").header("Authorization", "Bearer " + secondToken)
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"runId":"%s","expectedRevision":%s,"outcomes":[
                                  {"resultId":"%s","coverage":"SUPPORTED","category":"RULE","evidenceIds":["%s"],"summary":"%s"},
                                  {"resultId":"%s","coverage":"SUPPORTED","category":"FACT","evidenceIds":["%s"],"summary":"The analyst records the matched total."}],
                                  "nextAction":{"type":"PROPOSE_COMPLETION"}}
                                """.formatted(secondRun.get("id"), secondRun.get("expected_revision"), resultId,
                                secondEvidenceId, claim, nextResult, secondEvidenceId)))
                .andExpect(status().isOk()).andExpect(content().json("{\"event\":\"completion_confirmation_ready\"}"));

        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from result_outcomes where result_id=? and summary=?", Integer.class, resultId, claim))
                .isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                select count(*) from result_outcome_evidence e join result_outcomes o on o.id=e.outcome_id
                where o.result_id=? and o.summary=?
                """, Integer.class, resultId, claim)).isEqualTo(2);
        mockMvc.perform(post("/interview/confirm-completion").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(post("/test/extraction/run-once")).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                select count(*) from knowledge_versions v join knowledge_items k on k.id=v.knowledge_id
                where k.package_id=(select id from findings_packages where session_id=?) and v.claim=?
                """, Integer.class, participant.sessionId(), claim)).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                select count(*) from knowledge_evidence e join knowledge_items k on k.id=e.knowledge_id
                join knowledge_versions v on v.knowledge_id=k.id and v.version=e.knowledge_version
                where k.package_id=(select id from findings_packages where session_id=?) and v.claim=?
                """, Integer.class, participant.sessionId(), claim)).isEqualTo(2);
    }

    @Test
    void runtime_context_excludes_withdrawn_unanswered_questions_but_retains_accepted_evidence() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var mission = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, mission, 1);
        var participant = beginInterview(access, mission, "context-repair@example.com");
        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "Our team tracks client deadlines manually."))
                .andExpect(status().is3xxRedirection());
        jdbcTemplate.update("""
                insert into questions(id,organization_id,session_id,item_id,sequence,text,active)
                select ?,organization_id,session_id,item_id,2,'Assess the unseen candidate inventory',false
                from questions where session_id=? and sequence=1
                """, UUID.randomUUID(), participant.sessionId());
        var run = jdbcTemplate.queryForMap("select id,expected_revision from runtime_runs where session_id=?", participant.sessionId());
        var context = runtimeTurnContext.project((UUID) run.get("id"), participant.sessionId(), 1,
                ((Number) run.get("expected_revision")).intValue());
        org.assertj.core.api.Assertions.assertThat(context.get("questions_and_evidence").toString())
                .contains("Our team tracks client deadlines manually.")
                .doesNotContain("Assess the unseen candidate inventory");
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from questions where session_id=?", Integer.class, participant.sessionId())).isEqualTo(2);
    }

    @Test
    void turn_scoped_runtime_capability_commits_once_and_replays_only_the_stable_event() throws Exception {
        var access = authorizeHarness("findworks:read findworks:write");
        var missionId = submitMission(access, UUID.randomUUID()).path("mission_id").asText();
        approveMission(access, missionId, 1);
        var participant = beginInterview(access, missionId, "capability@example.com");
        var nextItem = UUID.randomUUID();
        jdbcTemplate.update("insert into investigation_items values(?,?,1,1,'What happens next?','The next step matters.','HIGH',true,'The ledger check is complete.','The next recorded step')",
                nextItem, UUID.fromString(missionId));
        var nextResult = UUID.randomUUID();
        jdbcTemplate.update("insert into investigation_results values(?,(select organization_id from interview_sessions where id=?),?,?, 'UNADDRESSED')",
                nextResult, participant.sessionId(), participant.sessionId(), nextItem);
        mockMvc.perform(post("/interview/answer").session(participant.session()).cookie(participant.cookie())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .param("answer", "The finance analyst checks the source ledger."))
                .andExpect(status().is3xxRedirection());
        var run = jdbcTemplate.queryForMap("select id,expected_revision from runtime_runs where session_id=?", participant.sessionId());
        jdbcTemplate.update("update runtime_runs set error_code='MODEL_FAILURE' where id=?", run.get("id"));
        var resultId = jdbcTemplate.queryForObject("""
                select r.id from questions q join investigation_results r on r.item_id=q.item_id and r.session_id=q.session_id
                where q.session_id=? and q.sequence=1
                """, UUID.class, participant.sessionId());
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
                        resultId, evidenceId, resultId, evidenceId, nextResult);
        for (int call = 0; call < 2; call++) {
            mockMvc.perform(post("/internal/runtime/turn").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andExpect(content().json("{\"event\":\"question_ready\"}"));
        }
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from questions where session_id=? and sequence=2", Integer.class, participant.sessionId())).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select error_code is null from runtime_runs where id=?", Boolean.class, run.get("id"))).isTrue();
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
                                {"runId":"%s","expectedRevision":%s,"outcomes":[],
                                  "nextAction":{"type":"ASK_QUESTION","resultId":"%s","text":"Is the source ledger authoritative?",
                                  "responseMode":"YES_NO"}}
                                """.formatted(nextRun.get("id"), nextRun.get("expected_revision"), nextResult)))
                .andExpect(status().isOk()).andExpect(content().json("{\"event\":\"question_ready\"}"));
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from result_outcomes where result_id=? and coverage in ('UNKNOWN','ASSUMPTION')", Integer.class, resultId)).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "select count(*) from unresolved where result_id=? and kind='UNKNOWN'", Integer.class, resultId)).isEqualTo(1);
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
                    "data_use_summary":"Reviewed findings and bounded source excerpts may be exported to the Investigator project."
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
