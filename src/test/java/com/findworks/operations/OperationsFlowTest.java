package com.findworks.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.findworks.operations.OperationalSignalSource.Signal;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
        "findworks.shaping.worker-cron=-",
        "findworks.invitation.worker-cron=-",
        "findworks.retention.worker-cron=-",
        "findworks.operations.monitor-cron=-",
        "findworks.operations.operator-id=70000000-0000-0000-0000-000000000033",
        "findworks.operations.break-glass-key-id=test-key-1",
        "findworks.operations.break-glass-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
@Testcontainers
class OperationsFlowTest {

    private static final UUID ORGANISATION = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID OWNER = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID OPERATOR = UUID.fromString("70000000-0000-0000-0000-000000000033");
    private static final UUID DISCOVERY = UUID.fromString("40000000-0000-0000-0000-000000000033");
    private static final UUID MISSION = UUID.fromString("50000000-0000-0000-0000-000000000033");
    private static final UUID ITEM = UUID.fromString("60000000-0000-0000-0000-000000000033");
    private static final UUID PARTICIPANT = UUID.fromString("80000000-0000-0000-0000-000000000033");
    private static final UUID SESSION = UUID.fromString("90000000-0000-0000-0000-000000000033");
    private static final UUID EVIDENCE = UUID.fromString("a0000000-0000-0000-0000-000000000033");
    private static final String EMAIL_CANARY = "person+private-canary@example.test";
    private static final String ANSWER_CANARY = "ANSWER-CANARY-PRIVATE-33";
    private static final String REASON_CANARY = "REASON-CANARY-PRIVATE-33";
    private static final String[] FORBIDDEN = {
            "PROMPT-CANARY-PRIVATE-33", "QUESTION-CANARY-PRIVATE-33", ANSWER_CANARY,
            "QUOTE-CANARY-PRIVATE-33", EMAIL_CANARY, "TOKEN-CANARY-PRIVATE-33",
            "MODEL-ARGS-CANARY-PRIVATE-33", "TOOL-ARGS-CANARY-PRIVATE-33",
            "PROVIDER-ERROR-CANARY-PRIVATE-33", REASON_CANARY
    };

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired JdbcClient jdbc;
    @Autowired OperationsRepository operations;
    @Autowired OperationalTelemetry telemetry;
    @Autowired MeterRegistry meters;
    @Autowired ProtectedReason reasons;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM operational_alerts").update();
        jdbc.sql("DELETE FROM discoveries").update();
        jdbc.sql("DELETE FROM audit_records").update();
        seedEvidence();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
        logs.start();
    }

    @AfterEach
    void stopLogs() {
        logs.stop();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
    }

    @Test
    void everyOperationalSignalStaysContentFreeAndBreakGlassNeedsApproval() {
        var resource = UUID.randomUUID();
        var signals = Arrays.stream(OperationalTelemetry.AlertKind.values())
                .map(kind -> new Signal(kind, OperationalTelemetry.SafeError.UNAVAILABLE, resource))
                .toList();

        var firing = operations.reconcile(signals);
        firing.forEach(event -> telemetry.alert(event.kind(), event.firing(), event.error(), event.resourceId()));
        assertThat(firing).hasSize(OperationalTelemetry.AlertKind.values().length);
        assertThat(operations.reconcile(signals)).isEmpty();

        var resolved = operations.reconcile(java.util.List.of());
        resolved.forEach(event -> telemetry.alert(event.kind(), event.firing(), event.error(), event.resourceId()));
        assertThat(resolved).hasSize(OperationalTelemetry.AlertKind.values().length);
        telemetry.job(OperationalTelemetry.JobKind.INTERVIEW_RUNTIME, OperationalTelemetry.Outcome.FAILED,
                OperationalTelemetry.SafeError.EXHAUSTED, 3, java.time.Duration.ofSeconds(2),
                resource, UUID.randomUUID(), UUID.randomUUID());
        telemetry.modelUsage(10, 4, 25, true);
        telemetry.request(OperationalTelemetry.HttpMethod.GET, OperationalTelemetry.HttpRoute.INTERVIEW,
                OperationalTelemetry.Outcome.DENIED, java.time.Duration.ofMillis(4), UUID.randomUUID());

        var request = UUID.randomUUID();
        var encrypted = reasons.encrypt(REASON_CANARY, request + ":" + EVIDENCE + ":" + OPERATOR);
        jdbc.sql("""
                INSERT INTO break_glass_requests (
                    id, organisation_id, evidence_id, operator_id, reason_code,
                    reason_key_id, reason_nonce, reason_ciphertext, requested_at, expires_at
                ) VALUES (?, ?, ?, ?, 'incident_diagnosis', ?, ?, ?, now(), now() + interval '1 hour')
                """).params(request, ORGANISATION, EVIDENCE, OPERATOR, encrypted.keyId(),
                encrypted.nonce(), encrypted.ciphertext()).update();

        assertThatThrownBy(() -> operations.viewBreakGlass(request, "incident_diagnosis"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(operations.reviewBreakGlass(request, "investigator@findworks.local").reason())
                .isEqualTo(REASON_CANARY);
        operations.decideBreakGlass(request, true, "investigator@findworks.local");
        assertThat(operations.viewBreakGlass(request, "incident_diagnosis")).isEqualTo(ANSWER_CANARY);
        operations.revokeBreakGlass(request, "incident_diagnosis");
        assertThatThrownBy(() -> operations.viewBreakGlass(request, "incident_diagnosis"))
                .isInstanceOf(IllegalArgumentException.class);

        var ciphertext = jdbc.sql("SELECT reason_ciphertext FROM break_glass_requests WHERE id = ?")
                .param(request).query(byte[].class).single();
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain(REASON_CANARY);
        assertContentFree(allOperationalChannels());
    }

    private String allOperationalChannels() {
        var logged = logs.list.stream().map(event -> event.getFormattedMessage() + " "
                + event.getKeyValuePairs()).reduce("", (left, right) -> left + " " + right);
        var metricIds = meters.getMeters().stream().map(meter -> meter.getId().toString())
                .reduce("", (left, right) -> left + " " + right);
        var alerts = jdbc.sql("""
                SELECT coalesce(string_agg(alert_kind || ':' || safe_error_class || ':' ||
                    coalesce(affected_resource_id::text, ''), ' '), '') FROM operational_alerts
                """).query(String.class).single();
        var audit = jdbc.sql("""
                SELECT coalesce(string_agg(action || ':' || resource_kind || ':' || outcome || ':' ||
                    coalesce(resource_id::text, ''), ' '), '') FROM audit_records
                """).query(String.class).single();
        return logged + " " + metricIds + " " + alerts + " " + audit;
    }

    private static void assertContentFree(String channels) {
        assertThat(channels).doesNotContain(FORBIDDEN);
    }

    private void seedEvidence() {
        jdbc.sql("""
                INSERT INTO discoveries (id, organisation_id, owner_membership_id, title, objective)
                VALUES (?, ?, ?, 'PROMPT-CANARY-PRIVATE-33', 'Private operational canary')
                """).params(DISCOVERY, ORGANISATION, OWNER).update();
        jdbc.sql("""
                INSERT INTO interview_missions (
                    id, organisation_id, discovery_id, lineage_id, version, status,
                    interviewee_name, interviewee_email, objective, desired_outcome,
                    interviewee_relevance, completion_criteria, expected_commitment,
                    data_use_summary, approved_at
                ) VALUES (?, ?, ?, ?, 1, 'approved', 'Canary participant', NULL,
                          'QUESTION-CANARY-PRIVATE-33', 'QUOTE-CANARY-PRIVATE-33',
                          'MODEL-ARGS-CANARY-PRIVATE-33', 'TOOL-ARGS-CANARY-PRIVATE-33',
                          '20 minutes', 'PROVIDER-ERROR-CANARY-PRIVATE-33', now())
                """).params(MISSION, ORGANISATION, DISCOVERY, MISSION).update();
        jdbc.sql("""
                INSERT INTO investigation_items (
                    id, organisation_id, interview_mission_id, position, knowledge_gap,
                    required, importance, priority, relevant_context
                ) VALUES (?, ?, ?, 0, 'Private canary gap', true, 'Private', 'high', 'Private')
                """).params(ITEM, ORGANISATION, MISSION).update();
        jdbc.sql("""
                INSERT INTO discovery_participants (id, organisation_id, discovery_id, intended_name, email)
                VALUES (?, ?, ?, 'Canary participant', ?)
                """).params(PARTICIPANT, ORGANISATION, DISCOVERY, EMAIL_CANARY).update();
        jdbc.sql("""
                INSERT INTO interview_sessions (
                    id, organisation_id, discovery_id, interview_mission_id, participant_id
                ) VALUES (?, ?, ?, ?, ?)
                """).params(SESSION, ORGANISATION, DISCOVERY, MISSION, PARTICIPANT).update();
        jdbc.sql("""
                INSERT INTO evidence (
                    id, organisation_id, discovery_id, interview_session_id,
                    interview_mission_id, participant_id, investigation_item_id,
                    source_type, answer
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'legacy', ?)
                """).params(EVIDENCE, ORGANISATION, DISCOVERY, SESSION, MISSION,
                PARTICIPANT, ITEM, ANSWER_CANARY).update();
    }
}
