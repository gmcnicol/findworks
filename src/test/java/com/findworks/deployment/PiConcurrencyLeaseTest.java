package com.findworks.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.findworks.shaping.ShapingRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "findworks.shaping.worker-cron=-",
        "findworks.invitation.worker-cron=-",
        "findworks.retention.worker-cron=-"
})
@Testcontainers
class PiConcurrencyLeaseTest {

    private static final UUID ORGANISATION =
            UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER =
            UUID.fromString("30000000-0000-0000-0000-000000000001");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired JdbcClient jdbc;
    @Autowired ShapingRepository shaping;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM discoveries").update();
        jdbc.sql("DELETE FROM audit_records").update();
        for (var position = 0; position < 3; position++) {
            seed(position);
        }
    }

    @Test
    void twoDatabaseSlotsHeartbeatAndExcessWorkWaitsForCleanup() {
        var first = shaping.claimNext();
        var second = shaping.claimNext();
        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(shaping.claimNext()).isNull();
        assertThat(jdbc.sql("SELECT count(*) FROM pi_worker_slots WHERE lease_owner IS NOT NULL")
                .query(Integer.class).single()).isEqualTo(2);

        assertThat(shaping.heartbeat(first)).isTrue();
        assertThat(jdbc.sql("SELECT heartbeat_at IS NOT NULL FROM shaping_runtime_work WHERE id = ?")
                .param(first.id()).query(Boolean.class).single()).isTrue();

        shaping.complete(first, "A safe completed result.");
        var third = shaping.claimNext();
        assertThat(third).isNotNull();
        assertThat(third.id()).isNotIn(first.id(), second.id());

        jdbc.sql("UPDATE shaping_runtime_work SET lease_until = now() - interval '1 second' WHERE id = ?")
                .param(second.id()).update();
        jdbc.sql("UPDATE pi_worker_slots SET lease_expires_at = now() - interval '1 second' WHERE shaping_work_id = ?")
                .param(second.id()).update();
        var reclaimed = shaping.claimNext();
        assertThat(reclaimed.id()).isEqualTo(second.id());
        assertThat(reclaimed.leaseOwner()).isNotEqualTo(second.leaseOwner());
        assertThat(shaping.heartbeat(second)).isFalse();
    }

    @Test
    void webDatabaseRoleCannotClaimWorkerLeaseOrPiSlot() {
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL ROLE findworks_application").update();
            jdbc.sql("SELECT set_config('findworks.organisation_id', ?, true)")
                    .param(ORGANISATION.toString()).query(String.class).single();
            jdbc.sql("""
                    UPDATE shaping_runtime_work SET status = 'running', attempts = attempts + 1,
                        lease_owner = gen_random_uuid(), lease_until = now() + interval '2 minutes'
                    WHERE id = (SELECT id FROM shaping_runtime_work ORDER BY created_at LIMIT 1)
                    """).update();
        })).hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.sql("SET LOCAL ROLE findworks_application").update();
            jdbc.sql("SELECT set_config('findworks.organisation_id', ?, true)")
                    .param(ORGANISATION.toString()).query(String.class).single();
            jdbc.sql("UPDATE pi_worker_slots SET updated_at = now() WHERE slot_number = 1").update();
        })).hasRootCauseInstanceOf(java.sql.SQLException.class);
    }

    private void seed(int position) {
        var discovery = UUID.randomUUID();
        var session = UUID.randomUUID();
        var message = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO discoveries (id, organisation_id, owner_membership_id, title, objective)
                VALUES (?, ?, ?, ?, 'Exercise deployment lease')
                """).params(discovery, ORGANISATION, OWNER, "Deployment lease " + position).update();
        jdbc.sql("""
                INSERT INTO discovery_shaping_sessions (id, organisation_id, discovery_id)
                VALUES (?, ?, ?)
                """).params(session, ORGANISATION, discovery).update();
        jdbc.sql("""
                INSERT INTO discovery_shaping_messages
                    (id, organisation_id, shaping_session_id, position, author_kind, author_id, content)
                VALUES (?, ?, ?, 0, 'investigator', ?, 'Please shape this Discovery')
                """).params(message, ORGANISATION, session, OWNER).update();
        jdbc.sql("""
                INSERT INTO shaping_runtime_work
                    (id, organisation_id, shaping_session_id, trigger_message_id, created_at)
                VALUES (?, ?, ?, ?, now() + (? * interval '1 millisecond'))
                """).params(UUID.randomUUID(), ORGANISATION, session, message, position).update();
    }
}
