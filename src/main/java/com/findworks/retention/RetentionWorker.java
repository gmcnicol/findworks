package com.findworks.retention;

import com.findworks.deployment.WorkerExecution;
import com.findworks.interview.InvitationDeliveryProvider;
import com.findworks.interview.InvitationProperties;
import com.findworks.operations.CorrelationContext;
import com.findworks.operations.OperationalTelemetry;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${findworks.process-role:local}' == 'local' || '${findworks.process-role:local}' == 'worker'")
public final class RetentionWorker {

    private final RetentionRepository repository;
    private final InvitationDeliveryProvider provider;
    private final InvitationProperties invitations;
    private final WorkerExecution execution;
    private final CorrelationContext correlations;
    private final OperationalTelemetry telemetry;

    RetentionWorker(RetentionRepository repository, InvitationDeliveryProvider provider,
            InvitationProperties invitations, WorkerExecution execution,
            CorrelationContext correlations, OperationalTelemetry telemetry) {
        this.repository = repository;
        this.provider = provider;
        this.invitations = invitations;
        this.execution = execution;
        this.correlations = correlations;
        this.telemetry = telemetry;
    }

    @Scheduled(cron = "${findworks.retention.worker-cron:0 0 * * * *}")
    public void run() {
        repository.schedule();
        sendWarning();
        purgeNext();
        repository.pruneAudit();
    }

    public void sendWarning() {
        var warning = repository.claimWarning();
        if (warning == null) {
            return;
        }
        var started = System.nanoTime();
        try (var ignored = correlations.open(warning.executionCorrelationId())) {
            execution.withHeartbeat(() -> repository.heartbeatWarning(warning), () ->
                    provider.submit(new InvitationDeliveryProvider.Delivery(
                            invitations.requiredSender(), warning.recipient(),
                            "FindWorks Discovery deletion warning",
                            "Your Discovery '" + warning.discoveryTitle() + "' is scheduled for deletion on "
                                    + warning.dueAt() + ". Contact support before then to extend retention.",
                            "retention-warning-" + warning.discoveryId() + "-" + warning.dueAt())));
            repository.warningSent(warning);
            record(warning, OperationalTelemetry.Outcome.SUCCESS,
                    OperationalTelemetry.SafeError.NONE, started);
        } catch (WorkerExecution.LeaseLost lost) {
            record(warning, OperationalTelemetry.Outcome.RETRY,
                    OperationalTelemetry.SafeError.TIMEOUT, started);
            return;
        } catch (InvitationDeliveryProvider.Failure failure) {
            repository.warningFailed(warning, failure.errorClass());
            record(warning, OperationalTelemetry.Outcome.FAILED,
                    OperationalTelemetry.SafeError.FAILED, started);
        } catch (Exception failure) {
            repository.warningFailed(warning, "provider_unavailable");
            record(warning, OperationalTelemetry.Outcome.FAILED,
                    OperationalTelemetry.SafeError.UNAVAILABLE, started);
        }
    }

    public void purgeNext() {
        var work = repository.claimPurge();
        if (work == null) {
            return;
        }
        var started = System.nanoTime();
        try (var ignored = correlations.open(work.executionCorrelationId())) {
            execution.withHeartbeat(() -> repository.heartbeatPurge(work), () -> {
                repository.purge(work);
                return null;
            });
            record(work, OperationalTelemetry.Outcome.SUCCESS,
                    OperationalTelemetry.SafeError.NONE, started);
        } catch (WorkerExecution.LeaseLost lost) {
            record(work, OperationalTelemetry.Outcome.RETRY,
                    OperationalTelemetry.SafeError.TIMEOUT, started);
            return;
        } catch (RuntimeException failure) {
            repository.purgeFailed(work);
            record(work, OperationalTelemetry.Outcome.FAILED,
                    OperationalTelemetry.SafeError.FAILED, started);
        } catch (Exception failure) {
            repository.purgeFailed(work);
            record(work, OperationalTelemetry.Outcome.FAILED,
                    OperationalTelemetry.SafeError.FAILED, started);
        }
    }

    private void record(RetentionRepository.Warning work, OperationalTelemetry.Outcome outcome,
            OperationalTelemetry.SafeError error, long started) {
        telemetry.job(OperationalTelemetry.JobKind.RETENTION_WARNING, outcome, error, work.attempt(),
                Duration.ofNanos(System.nanoTime() - started), work.id(), work.originCorrelationId(),
                work.executionCorrelationId());
    }

    private void record(RetentionRepository.Purge work, OperationalTelemetry.Outcome outcome,
            OperationalTelemetry.SafeError error, long started) {
        telemetry.job(OperationalTelemetry.JobKind.PURGE, outcome, error, work.attempt(),
                Duration.ofNanos(System.nanoTime() - started), work.id(), work.originCorrelationId(),
                work.executionCorrelationId());
    }
}
