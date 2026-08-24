package com.findworks.shaping;

import com.findworks.interview.InterviewRuntimeRepository;
import com.findworks.interview.FindingsRepository;
import com.findworks.runtime.FindingsExtractionRunner;
import com.findworks.runtime.InterviewTurnRunner;
import com.findworks.runtime.RuntimeFailure;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ShapingWorker {

    private final ShapingRepository repository;
    private final InterviewRuntimeRepository interviews;
    private final InterviewTurnRunner interviewRunner;
    private final FindingsRepository findings;
    private final FindingsExtractionRunner findingsRunner;
    private final PiShapingAdapter pi;

    ShapingWorker(ShapingRepository repository, InterviewRuntimeRepository interviews,
            InterviewTurnRunner interviewRunner, FindingsRepository findings,
            FindingsExtractionRunner findingsRunner, PiShapingAdapter pi) {
        this.repository = repository;
        this.interviews = interviews;
        this.interviewRunner = interviewRunner;
        this.findings = findings;
        this.findingsRunner = findingsRunner;
        this.pi = pi;
    }

    @Scheduled(cron = "${findworks.shaping.worker-cron:*/1 * * * * *}")
    public void runNext() {
        var interview = interviews.claimNext();
        if (interview != null) {
            String runtimeVersion = null;
            try {
                if ("findings_extraction".equals(interview.workKind())) {
                    runtimeVersion = findingsRunner.runtimeVersion();
                    var prepared = findings.prepare(interview, runtimeVersion);
                    var result = findingsRunner.run(new FindingsExtractionRunner.Request(
                            prepared.context(), prepared.credential(), prepared.credentialExpiresAt()));
                    findings.complete(interview, result);
                } else {
                    runtimeVersion = interviewRunner.runtimeVersion();
                    var prepared = interviews.prepare(interview, runtimeVersion);
                    var result = interviewRunner.run(new InterviewTurnRunner.Request(
                            prepared.context(), prepared.checkpoint(), prepared.credential(),
                            prepared.credentialExpiresAt()));
                    interviews.complete(interview, result);
                }
            } catch (RuntimeFailure failure) {
                interviews.fail(interview, runtimeVersion, failure);
            } catch (Exception error) {
                interviews.fail(interview, runtimeVersion,
                        new RuntimeFailure(RuntimeFailure.Kind.INVALID_OUTPUT, 0));
            }
            return;
        }
        var work = repository.claimNext();
        if (work == null) {
            return;
        }
        try {
            var turn = pi.followUp(repository.context(work));
            if (turn.proposal() == null) {
                repository.complete(work, turn.question());
            } else {
                repository.complete(work, turn.proposal());
            }
        } catch (Exception error) {
            repository.fail(work);
        }
    }
}
