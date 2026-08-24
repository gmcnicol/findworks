package com.findworks.interview;

import com.findworks.deployment.WorkerExecution;
import com.findworks.operations.CorrelationContext;
import com.findworks.operations.OperationalTelemetry;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${findworks.process-role:local}' == 'local' || '${findworks.process-role:local}' == 'worker'")
public final class InvitationDeliveryWorker {

    private final InvitationRepository repository;
    private final InvitationDeliveryProvider provider;
    private final InvitationProperties properties;
    private final InvitationToken tokens;
    private final WorkerExecution execution;
    private final CorrelationContext correlations;
    private final OperationalTelemetry telemetry;

    InvitationDeliveryWorker(InvitationRepository repository, InvitationDeliveryProvider provider,
            InvitationProperties properties, InvitationToken tokens, WorkerExecution execution,
            CorrelationContext correlations, OperationalTelemetry telemetry) {
        this.repository = repository;
        this.provider = provider;
        this.properties = properties;
        this.tokens = tokens;
        this.execution = execution;
        this.correlations = correlations;
        this.telemetry = telemetry;
    }

    @Scheduled(cron = "${findworks.invitation.worker-cron:*/1 * * * * *}")
    public void runNext() {
        var work = repository.claimNext();
        if (work == null) {
            return;
        }
        var started = System.nanoTime();
        try (var ignored = correlations.open(work.executionCorrelationId())) {
            var token = tokens.token(work.invitationId(), work.tokenKeyId());
            var link = properties.origin().resolve("/i/" + token).toString();
            var accepted = execution.withHeartbeat(() -> repository.heartbeat(work), () ->
                    provider.submit(new InvitationDeliveryProvider.Delivery(
                            properties.requiredSender(), work.recipientEmail(),
                            "Your FindWorks Interview Mission invitation",
                            "Hello " + work.intervieweeName()
                                    + ",\n\nUse this private seven-day link to contribute:\n" + link + "\n",
                            "invitation-" + work.invitationId())));
            repository.accepted(work, accepted.providerMessageId());
            record(work, OperationalTelemetry.Outcome.SUCCESS, OperationalTelemetry.SafeError.NONE, started);
        } catch (WorkerExecution.LeaseLost lost) {
            record(work, OperationalTelemetry.Outcome.RETRY, OperationalTelemetry.SafeError.TIMEOUT, started);
            return;
        } catch (InvitationDeliveryProvider.Failure failure) {
            repository.failed(work, failure.errorClass());
            record(work, OperationalTelemetry.Outcome.FAILED, OperationalTelemetry.SafeError.FAILED, started);
        } catch (Exception error) {
            repository.failed(work, "provider_unavailable");
            record(work, OperationalTelemetry.Outcome.FAILED,
                    OperationalTelemetry.SafeError.UNAVAILABLE, started);
        }
    }

    private void record(InvitationRepository.Work work, OperationalTelemetry.Outcome outcome,
            OperationalTelemetry.SafeError error, long started) {
        telemetry.job(OperationalTelemetry.JobKind.INVITATION_EMAIL, outcome, error,
                work.attemptNumber(), Duration.ofNanos(System.nanoTime() - started), work.jobId(),
                work.originCorrelationId(), work.executionCorrelationId());
    }
}
