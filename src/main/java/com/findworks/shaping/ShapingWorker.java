package com.findworks.shaping;

import com.findworks.deployment.WorkerExecution;
import com.findworks.interview.InterviewRuntimeRepository;
import com.findworks.interview.FindingsRepository;
import com.findworks.operations.CorrelationContext;
import com.findworks.operations.OperationalTelemetry;
import com.findworks.runtime.FindingsExtractionRunner;
import com.findworks.runtime.InterviewTurnRunner;
import com.findworks.runtime.RuntimeFailure;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${findworks.process-role:local}' == 'local' || '${findworks.process-role:local}' == 'worker'")
public class ShapingWorker {

    private final ShapingRepository repository;
    private final InterviewRuntimeRepository interviews;
    private final InterviewTurnRunner interviewRunner;
    private final FindingsRepository findings;
    private final FindingsExtractionRunner findingsRunner;
    private final PiShapingAdapter pi;
    private final WorkerExecution execution;
    private final CorrelationContext correlations;
    private final OperationalTelemetry telemetry;

    ShapingWorker(ShapingRepository repository, InterviewRuntimeRepository interviews,
            InterviewTurnRunner interviewRunner, FindingsRepository findings,
            FindingsExtractionRunner findingsRunner, PiShapingAdapter pi, WorkerExecution execution,
            CorrelationContext correlations, OperationalTelemetry telemetry) {
        this.repository = repository;
        this.interviews = interviews;
        this.interviewRunner = interviewRunner;
        this.findings = findings;
        this.findingsRunner = findingsRunner;
        this.pi = pi;
        this.execution = execution;
        this.correlations = correlations;
        this.telemetry = telemetry;
    }

    @Scheduled(cron = "${findworks.shaping.worker-cron:*/1 * * * * *}")
    public void runNext() {
        var interview = interviews.claimNext();
        if (interview != null) {
            execution.submitPi(() -> runInterview(interview));
            return;
        }
        var work = repository.claimNext();
        if (work == null) {
            return;
        }
        execution.submitPi(() -> runShaping(work));
    }

    private void runInterview(InterviewRuntimeRepository.Work interview) {
        var started = System.nanoTime();
        String runtimeVersion = null;
        try (var ignored = correlations.open(interview.executionCorrelationId())) {
            if ("findings_extraction".equals(interview.workKind())) {
                runtimeVersion = findingsRunner.runtimeVersion();
                var prepared = findings.prepare(interview, runtimeVersion);
                var result = execution.withHeartbeat(() -> interviews.heartbeat(interview), () ->
                        findingsRunner.run(new FindingsExtractionRunner.Request(
                                prepared.context(), prepared.credential(), prepared.credentialExpiresAt())));
                findings.complete(interview, result);
            } else {
                runtimeVersion = interviewRunner.runtimeVersion();
                var prepared = interviews.prepare(interview, runtimeVersion);
                var result = execution.withHeartbeat(() -> interviews.heartbeat(interview), () ->
                        interviewRunner.run(new InterviewTurnRunner.Request(
                                prepared.context(), prepared.checkpoint(), prepared.credential(),
                                prepared.credentialExpiresAt())));
                interviews.complete(interview, result);
            }
            record(interview, OperationalTelemetry.Outcome.SUCCESS,
                    OperationalTelemetry.SafeError.NONE, started);
        } catch (WorkerExecution.LeaseLost lost) {
            record(interview, OperationalTelemetry.Outcome.RETRY,
                    OperationalTelemetry.SafeError.TIMEOUT, started);
            return;
        } catch (RuntimeFailure failure) {
            interviews.fail(interview, runtimeVersion, failure);
            record(interview, OperationalTelemetry.Outcome.FAILED,
                    OperationalTelemetry.SafeError.FAILED, started);
        } catch (Exception error) {
            interviews.fail(interview, runtimeVersion,
                    new RuntimeFailure(RuntimeFailure.Kind.INVALID_OUTPUT, 0));
            record(interview, OperationalTelemetry.Outcome.FAILED,
                    OperationalTelemetry.SafeError.FAILED, started);
        }
    }

    private void runShaping(ShapingRepository.Work work) {
        var started = System.nanoTime();
        try (var ignored = correlations.open(work.executionCorrelationId())) {
            var turn = execution.withHeartbeat(() -> repository.heartbeat(work),
                    () -> pi.followUp(repository.context(work)));
            if (turn.proposal() == null) {
                repository.complete(work, turn.question());
            } else {
                repository.complete(work, turn.proposal());
            }
            record(work, OperationalTelemetry.Outcome.SUCCESS,
                    OperationalTelemetry.SafeError.NONE, started);
        } catch (WorkerExecution.LeaseLost lost) {
            record(work, OperationalTelemetry.Outcome.RETRY,
                    OperationalTelemetry.SafeError.TIMEOUT, started);
            return;
        } catch (Exception error) {
            repository.fail(work);
            record(work, OperationalTelemetry.Outcome.FAILED,
                    OperationalTelemetry.SafeError.FAILED, started);
        }
    }

    private void record(InterviewRuntimeRepository.Work work, OperationalTelemetry.Outcome outcome,
            OperationalTelemetry.SafeError error, long started) {
        var kind = "findings_extraction".equals(work.workKind())
                ? OperationalTelemetry.JobKind.FINDINGS_EXTRACTION
                : OperationalTelemetry.JobKind.INTERVIEW_RUNTIME;
        telemetry.modelUsage(0, 0, 0, false);
        telemetry.job(kind, outcome, error, work.executionAttempt(), elapsed(started),
                work.id(), work.originCorrelationId(), work.executionCorrelationId());
    }

    private void record(ShapingRepository.Work work, OperationalTelemetry.Outcome outcome,
            OperationalTelemetry.SafeError error, long started) {
        telemetry.job(OperationalTelemetry.JobKind.SHAPING, outcome, error, work.attempts(),
                elapsed(started), work.id(), work.originCorrelationId(), work.executionCorrelationId());
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }
}
