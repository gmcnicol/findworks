package com.findworks.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@Transactional
class EmailDeliveryServiceIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.4")
            .withDatabaseName("findworks").withUsername("findworks").withPassword("findworks");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("findworks.outbox-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("findworks.email-endpoint", () -> "disabled");
        registry.add("findworks.email-api-key", () -> "disabled");
    }

    @Autowired EmailDeliveryService email;
    @Autowired JdbcTemplate db;

    @Test
    void invitation_link_is_encrypted_and_delivery_stops_after_three_attempts() {
        var organization = UUID.fromString("11111111-1111-1111-1111-111111111111");
        var owner = UUID.fromString("f1111111-1111-1111-1111-111111111111");
        var discovery = UUID.randomUUID(); var mission = UUID.randomUUID(); var participant = UUID.randomUUID();
        var session = UUID.randomUUID(); var invitation = UUID.randomUUID();
        db.update("insert into discoveries(id,organization_id,owner_membership_id,title,objective) values(?,?,?,?,?)",
                discovery, organization, owner, "Outbox test", "Prove delivery recovery");
        db.update("insert into missions(id,organization_id,discovery_id,label,current_version,approved_version) values(?,?,?,?,1,1)",
                mission, organization, discovery, "Outbox test");
        db.update("insert into mission_versions(mission_id,organization_id,version,state,objective,desired_outcome,interviewee_role,interviewee_relevance,expected_minutes,data_use,created_by) values(?,?,1,'APPROVED','Test','Test','Tester','Relevant',10,'Test',?)",
                mission, organization, owner);
        db.update("insert into participants values(?,?,?,?,?)", participant, organization, discovery, "Clare", "clare@example.com");
        db.update("insert into interview_sessions(id,organization_id,discovery_id,mission_id,mission_version,participant_id) values(?,?,?,?,1,?)",
                session, organization, discovery, mission, participant);
        db.update("insert into invitations values(?,?,?,?,?,'PENDING',now()+interval '7 days',null,null,null)",
                invitation, organization, session, "clare@example.com", "0".repeat(64));

        var link = "https://findworks.example/invite/secret-token";
        email.queue(invitation, organization, "clare@example.com", link);
        var encrypted = db.queryForObject("select encrypted_link from email_outbox where invitation_id=?", byte[].class, invitation);
        assertThat(new String(encrypted, StandardCharsets.UTF_8)).doesNotContain(link);

        db.update("update email_outbox set state='RUNNING',attempts=1,lease_until=now()-interval '1 second' where invitation_id=?", invitation);
        for (int attempt = 2; attempt <= 3; attempt++) {
            assertThat(email.deliverNext()).isTrue();
            db.update("update email_outbox set available_at=now() where invitation_id=?", invitation);
        }
        assertThat(db.queryForObject("select state from email_outbox where invitation_id=?", String.class, invitation)).isEqualTo("FAILED");
        assertThat(db.queryForObject("select attempts from email_outbox where invitation_id=?", Integer.class, invitation)).isEqualTo(3);
        assertThat(db.queryForObject("select state from invitations where id=?", String.class, invitation)).isEqualTo("FAILED");
    }
}
