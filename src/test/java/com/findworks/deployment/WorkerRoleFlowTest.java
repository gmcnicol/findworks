package com.findworks.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "findworks.process-role=worker",
        "findworks.shaping.worker-cron=-",
        "findworks.invitation.worker-cron=-",
        "findworks.retention.worker-cron=-"
})
@Testcontainers
class WorkerRoleFlowTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired ApplicationContext application;
    @Autowired JdbcClient jdbc;

    @Test
    void workerHasHandlersButNoProductControllers() {
        assertThat(application.containsBean("shapingWorker")).isTrue();
        assertThat(application.containsBean("invitationDeliveryWorker")).isTrue();
        assertThat(application.containsBean("retentionWorker")).isTrue();
        assertThat(application.containsBean("homeController")).isFalse();
        assertThat(application.containsBean("discoveryController")).isFalse();
        assertThat(application.containsBean("interviewController")).isFalse();
    }

    @Test
    void workerAloneCanDeleteRetentionGraphRoots() {
        assertThat(jdbc.sql("""
                SELECT bool_and(has_table_privilege('findworks_worker', table_name, 'DELETE'))
                FROM (VALUES ('invitations'), ('interview_sessions'),
                    ('discovery_participants'), ('audit_records')) AS required(table_name)
                """).query(Boolean.class).single()).isTrue();
        assertThat(jdbc.sql("SELECT has_table_privilege('findworks_application', 'invitations', 'DELETE')")
                .query(Boolean.class).single()).isFalse();
    }
}
