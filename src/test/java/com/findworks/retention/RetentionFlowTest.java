package com.findworks.retention;

import static org.assertj.core.api.Assertions.assertThat;

import com.findworks.interview.InvitationDeliveryProvider;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "findworks.retention.worker-cron=-",
        "findworks.invitation.worker-cron=-",
        "findworks.shaping.worker-cron=-",
        "findworks.invitation.sender=notifications@example.com"
})
@Import(RetentionFlowTest.TimeConfiguration.class)
@Testcontainers
class RetentionFlowTest {

    private static final UUID ORGANISATION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final String EMAIL = "investigator@findworks.local";
    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired JdbcClient jdbc;
    @Autowired RetentionRepository retention;
    @Autowired RetentionWorker worker;
    @Autowired MutableClock clock;
    @Autowired RecordingProvider provider;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM discoveries").update();
        jdbc.sql("DELETE FROM deletion_ledger").update();
        jdbc.sql("DELETE FROM audit_records").update();
        provider.deliveries.clear();
        clock.set(START);
    }

    @Test
    void inactiveScheduleMovesWarnsOncePerDueSnapshotAndHonoursExtension() {
        var discovery = discovery("Retention clock", START);
        retention.schedule();
        var initialDue = due(discovery);
        assertThat(initialDue).isAfter(START.plusSeconds(179L * 86_400));

        clock.set(START.plusSeconds(100L * 86_400));
        jdbc.sql("UPDATE discoveries SET last_activity_at = ? WHERE id = ?")
                .params(java.sql.Timestamp.from(clock.instant()), discovery).update();
        retention.schedule();
        var movedDue = due(discovery);
        assertThat(movedDue).isAfter(initialDue);

        clock.set(movedDue.minusSeconds(14L * 86_400));
        retention.schedule();
        worker.sendWarning();
        retention.schedule();
        worker.sendWarning();
        assertThat(provider.deliveries).hasSize(1);
        assertThat(jdbc.sql("SELECT status FROM retention_warnings")
                .query(String.class).single()).isEqualTo("sent");

        var extended = movedDue.plusSeconds(30L * 86_400);
        retention.extend(discovery, extended, EMAIL);
        clock.set(extended.minusSeconds(14L * 86_400));
        retention.schedule();
        worker.sendWarning();
        assertThat(provider.deliveries).hasSize(2);
        assertThat(jdbc.sql("SELECT count(*) FROM retention_warnings")
                .query(Integer.class).single()).isEqualTo(2);

        clock.set(extended.minusSeconds(1));
        retention.schedule();
        assertThat(jdbc.sql("SELECT status FROM discoveries WHERE id = ?")
                .param(discovery).query(String.class).single()).isEqualTo("active");
        clock.set(extended);
        retention.schedule();
        assertThat(jdbc.sql("SELECT status || ':' || (purge_due_at <= ?::timestamptz + interval '7 days') FROM discoveries WHERE id = ?")
                .params(java.sql.Timestamp.from(clock.instant()), discovery).query(String.class).single())
                .isEqualTo("deletion_pending:true");
    }

    @Test
    void purgeLedgerSurvivesContentAndAuditExpiresAfterTwelveMonths() {
        var discovery = discovery("Delete me", START);
        retention.requestDiscoveryDeletion(discovery, EMAIL);
        var work = retention.claimPurge();
        retention.purgeFailed(work);
        assertThat(jdbc.sql("SELECT stage || ':' || attempts || ':' || (safe_error_class IS NOT NULL) "
                + "FROM deletion_ledger WHERE target_id = ?").param(discovery).query(String.class).single())
                .isEqualTo("blocked:1:true");
        assertThat(jdbc.sql("SELECT status FROM discoveries WHERE id = ?")
                .param(discovery).query(String.class).single()).isEqualTo("deletion_pending");
        work = retention.claimPurge();
        retention.purge(work);

        assertThat(jdbc.sql("SELECT count(*) FROM discoveries WHERE id = ?")
                .param(discovery).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT stage || ':' || (backup_expiry_due_at = completed_at + interval '30 days') "
                + "FROM deletion_ledger WHERE target_id = ?").param(discovery).query(String.class).single())
                .isEqualTo("completed:true");
        assertThat(jdbc.sql("SELECT count(*) FROM audit_records").query(Integer.class).single()).isGreaterThan(0);

        clock.set(Instant.parse("2030-12-31T23:59:59Z"));
        assertThat(retention.pruneAudit()).isZero();
        clock.set(Instant.parse("2031-01-01T00:00:00Z"));
        assertThat(retention.pruneAudit()).isGreaterThan(0);
        assertThat(jdbc.sql("SELECT count(*) FROM deletion_ledger WHERE target_id = ?")
                .param(discovery).query(Integer.class).single()).isEqualTo(1);
    }

    private UUID discovery(String title, Instant activity) {
        var id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO discoveries (
                    id, organisation_id, owner_membership_id, title, objective,
                    last_activity_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'Retention test', ?, ?, ?)
                """).params(id, ORGANISATION, OWNER, title, java.sql.Timestamp.from(activity),
                java.sql.Timestamp.from(activity), java.sql.Timestamp.from(activity)).update();
        return id;
    }

    private Instant due(UUID discovery) {
        return jdbc.sql("SELECT retention_due_at FROM discoveries WHERE id = ?")
                .param(discovery).query((rs, ignored) -> rs.getTimestamp(1).toInstant()).single();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        RecordingProvider recordingProvider() {
            return new RecordingProvider();
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant = START;

        void set(Instant value) {
            instant = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    static final class RecordingProvider implements InvitationDeliveryProvider {
        final List<Delivery> deliveries = new ArrayList<>();

        @Override
        public Accepted submit(Delivery delivery) {
            deliveries.add(delivery);
            return new Accepted("retention-message-" + deliveries.size());
        }
    }
}
