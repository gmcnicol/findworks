package com.findworks.web;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class InterviewLifecycleMigrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("findworks").withUsername("findworks").withPassword("findworks");

    @Test
    void v16_converts_terminated_sessions_without_losing_evidence_and_enforces_one_terminal_state() throws Exception {
        var flyway = Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .target("15").load();
        flyway.migrate();

        var organization = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var owner = UUID.fromString("f1111111-1111-1111-1111-111111111111");
        var discovery = UUID.randomUUID();
        var mission = UUID.randomUUID();
        var participant = UUID.randomUUID();
        var session = UUID.randomUUID();
        var evidence = UUID.randomUUID();
        var lastActivity = OffsetDateTime.parse("2026-01-02T03:04:05Z");

        try (var connection = postgres.createConnection(""); var statement = connection.createStatement()) {
            statement.execute("insert into discoveries(id,organization_id,owner_membership_id,title,objective) values('%s','%s','%s','Migration','Preserve work')"
                    .formatted(discovery, organization, owner));
            statement.execute("insert into missions(id,organization_id,discovery_id,label,current_version,approved_version) values('%s','%s','%s','Migration',1,1)"
                    .formatted(mission, organization, discovery));
            statement.execute("insert into mission_versions(mission_id,organization_id,version,state,objective,desired_outcome,interviewee_role,interviewee_relevance,data_use,created_by) values('%s','%s',1,'APPROVED','Test','Test','Expert','Relevant','Test','%s')"
                    .formatted(mission, organization, owner));
            statement.execute("insert into participants values('%s','%s','%s','Clare','clare@example.com')"
                    .formatted(participant, organization, discovery));
            statement.execute("insert into interview_sessions(id,organization_id,discovery_id,mission_id,mission_version,participant_id,state,last_activity_at) values('%s','%s','%s','%s',1,'%s','TERMINATED','%s')"
                    .formatted(session, organization, discovery, mission, participant, lastActivity));
            statement.execute("insert into evidence(id,organization_id,session_id,source_type,exact_text) values('%s','%s','%s','ANSWER','Retained migration Evidence')"
                    .formatted(evidence, organization, session));
            statement.execute("insert into access_grants(id,organization_id,session_id,token_hash) values(gen_random_uuid(),'%s','%s','%s')"
                    .formatted(organization, session, "a".repeat(64)));
            statement.execute("insert into invitations(id,organization_id,session_id,recipient_email,token_hash,state,expires_at) values(gen_random_uuid(),'%s','%s','clare@example.com','%s','DELIVERED',now()+interval '1 day')"
                    .formatted(organization, session, "b".repeat(64)));
            statement.execute("insert into clerk_accounts values('user_migration','clare@example.com',now())");
            statement.execute("insert into interview_account_access(id,organization_id,session_id,clerk_user_id) values(gen_random_uuid(),'%s','%s','user_migration')"
                    .formatted(organization, session));
        }

        Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()).load().migrate();

        try (var connection = postgres.createConnection("")) {
            try (var statement = connection.prepareStatement("select state,completed_at from interview_sessions where id=?")) {
                statement.setObject(1, session);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString("state")).isEqualTo("COMPLETED");
                    assertThat(result.getObject("completed_at", OffsetDateTime.class)).isEqualTo(lastActivity);
                }
            }
            try (var statement = connection.prepareStatement("select exact_text from evidence where id=?")) {
                statement.setObject(1, evidence);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo("Retained migration Evidence");
                }
            }
            try (var statement = connection.prepareStatement("""
                    select
                      (select count(*) from access_grants where session_id=? and revoked_at is null),
                      (select count(*) from interview_account_access where session_id=? and revoked_at is null),
                      (select count(*) from invitations where session_id=? and state<>'REVOKED')
                    """)) {
                statement.setObject(1, session);
                statement.setObject(2, session);
                statement.setObject(3, session);
                try (var result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isZero();
                    assertThat(result.getInt(2)).isZero();
                    assertThat(result.getInt(3)).isZero();
                }
            }
        }

        assertConstraintViolation("update interview_sessions set state='TERMINATED' where id='%s'".formatted(session));
        assertConstraintViolation("update interview_sessions set state='ACTIVE' where id='%s'".formatted(session));
    }

    private void assertConstraintViolation(String sql) {
        assertThatThrownBy(() -> {
            try (var connection = postgres.createConnection(""); var statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }).isInstanceOf(SQLException.class).extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23514");
    }
}
