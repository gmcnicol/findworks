package com.findworks.operations;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class OperationalTelemetry {

    private static final Logger LOG = LoggerFactory.getLogger("findworks.operations");
    private final MeterRegistry meters;
    private final String role;
    private final String environment;
    private final CorrelationContext correlations;

    public OperationalTelemetry(MeterRegistry meters,
            @Value("${findworks.process-role:local}") String role,
            @Value("${findworks.deployment.environment:local}") String environment,
            CorrelationContext correlations) {
        this.meters = meters;
        this.role = role;
        this.environment = environment;
        this.correlations = correlations;
    }

    public void request(HttpMethod method, HttpRoute route, Outcome outcome, Duration duration,
            UUID correlationId) {
        meters.counter("findworks.http.requests", "method", method.name().toLowerCase(),
                "route", route.name().toLowerCase(), "outcome", outcome.value).increment();
        meters.timer("findworks.http.duration", "method", method.name().toLowerCase(),
                "route", route.name().toLowerCase()).record(duration);
        LOG.atInfo().addKeyValue("event_kind", "request_settled")
                .addKeyValue("service_role", role).addKeyValue("deployment_environment", environment)
                .addKeyValue("method", method.name().toLowerCase()).addKeyValue("route", route.name().toLowerCase())
                .addKeyValue("outcome", outcome.value).addKeyValue("duration_ms", duration.toMillis())
                .addKeyValue("correlation_id", correlationId).log("request_settled");
    }

    public void job(JobKind kind, Outcome outcome, SafeError error, int attempt, Duration duration,
            UUID jobId, UUID originCorrelationId, UUID executionCorrelationId) {
        meters.counter("findworks.jobs", "kind", kind.value, "outcome", outcome.value,
                "error", error.value).increment();
        meters.timer("findworks.job.duration", "kind", kind.value).record(duration);
        LOG.atInfo().addKeyValue("event_kind", "job_settled")
                .addKeyValue("service_role", role).addKeyValue("deployment_environment", environment)
                .addKeyValue("job_kind", kind.value).addKeyValue("outcome", outcome.value)
                .addKeyValue("safe_error_class", error.value).addKeyValue("attempt", attempt)
                .addKeyValue("duration_ms", duration.toMillis()).addKeyValue("resource_id", jobId)
                .addKeyValue("origin_correlation_id", originCorrelationId)
                .addKeyValue("execution_correlation_id", executionCorrelationId).log("job_settled");
    }

    public void alert(AlertKind kind, boolean firing, SafeError error, UUID resourceId) {
        meters.counter("findworks.alert.transitions", "kind", kind.value,
                "state", firing ? "firing" : "resolved").increment();
        LOG.atWarn().addKeyValue("event_kind", "alert_transition")
                .addKeyValue("service_role", role).addKeyValue("deployment_environment", environment)
                .addKeyValue("alert_kind", kind.value).addKeyValue("state", firing ? "firing" : "resolved")
                .addKeyValue("safe_error_class", error.value).addKeyValue("resource_id", resourceId)
                .addKeyValue("correlation_id", correlations.current())
                .log("alert_transition");
    }

    public void modelUsage(long inputUnits, long outputUnits, long costMicros, boolean available) {
        meters.counter("findworks.model.input.units", "available", Boolean.toString(available))
                .increment(inputUnits);
        meters.counter("findworks.model.output.units", "available", Boolean.toString(available))
                .increment(outputUnits);
        meters.counter("findworks.model.cost.micros", "available", Boolean.toString(available))
                .increment(costMicros);
    }

    public enum HttpMethod { GET, POST, OTHER }
    public enum HttpRoute { HOME, DISCOVERY, MISSION, INTERVIEW, FINDINGS, OPERATIONS, HEALTH, OTHER }
    public enum Outcome {
        SUCCESS("success"), DENIED("denied"), FAILED("failed"), RETRY("retry");
        final String value;
        Outcome(String value) { this.value = value; }
    }
    public enum SafeError {
        NONE("none"), UNAVAILABLE("unavailable"), TIMEOUT("timeout"), EXHAUSTED("exhausted"),
        CAPACITY("capacity"), STALE("stale"), FAILED("failed"), OVERDUE("overdue");
        final String value;
        SafeError(String value) { this.value = value; }
    }
    public enum JobKind {
        SHAPING("shaping"), INTERVIEW_RUNTIME("interview_runtime"), FINDINGS_EXTRACTION("findings_extraction"),
        INVITATION_EMAIL("invitation_email"), RETENTION_WARNING("retention_warning"), PURGE("purge");
        final String value;
        JobKind(String value) { this.value = value; }
    }
    public enum AlertKind {
        WEB_OUTAGE("web_outage"), WORKER_OUTAGE("worker_outage"), DATABASE_OUTAGE("database_outage"),
        OLD_JOB("old_job"), RUNTIME_FAILURE("runtime_failure"), EMAIL_FAILURE("email_failure"),
        DISK_PRESSURE("disk_pressure"), BACKUP_FAILED("backup_failed"), BACKUP_STALE("backup_stale"),
        PURGE_OVERDUE("purge_overdue"), TELEMETRY_GAP("telemetry_gap");
        final String value;
        AlertKind(String value) { this.value = value; }
        public String value() { return value; }
    }
}
