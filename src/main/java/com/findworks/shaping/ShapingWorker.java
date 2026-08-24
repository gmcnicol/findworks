package com.findworks.shaping;

import com.findworks.deployment.WorkerExecution;
import com.findworks.interview.InterviewRuntimeRepository;
import com.findworks.interview.FindingsRepository;
import com.findworks.runtime.FindingsExtractionRunner;
import com.findworks.runtime.InterviewTurnRunner;
import com.findworks.runtime.RuntimeFailure;
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

    ShapingWorker(ShapingRepository repository, InterviewRuntimeRepository interviews,
            InterviewTurnRunner interviewRunner, FindingsRepository findings,
            FindingsExtractionRunner findingsRunner, PiShapingAdapter pi, WorkerExecution execution) {
        this.repository = repository;
        this.interviews = interviews;
        this.interviewRunner = interviewRunner;
        this.findings = findings;
        this.findingsRunner = findingsRunner;
        this.pi = pi;
        this.execution = execution;
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
        String runtimeVersion = null;
        try {
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
        } catch (WorkerExecution.LeaseLost lost) {
            return;
        } catch (RuntimeFailure failure) {
            interviews.fail(interview, runtimeVersion, failure);
        } catch (Exception error) {
            interviews.fail(interview, runtimeVersion,
                    new RuntimeFailure(RuntimeFailure.Kind.INVALID_OUTPUT, 0));
        }
    }

    private void runShaping(ShapingRepository.Work work) {
        try {
            var turn = execution.withHeartbeat(() -> repository.heartbeat(work),
                    () -> pi.followUp(repository.context(work)));
            if (turn.proposal() == null) {
                repository.complete(work, turn.question());
            } else {
                repository.complete(work, turn.proposal());
            }
        } catch (WorkerExecution.LeaseLost lost) {
            return;
        } catch (Exception error) {
            repository.fail(work);
        }
    }
}
