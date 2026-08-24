package com.findworks.shaping;

import com.findworks.interview.InterviewRuntimeRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ShapingWorker {

    private final ShapingRepository repository;
    private final InterviewRuntimeRepository interviews;
    private final PiShapingAdapter pi;

    ShapingWorker(ShapingRepository repository, InterviewRuntimeRepository interviews, PiShapingAdapter pi) {
        this.repository = repository;
        this.interviews = interviews;
        this.pi = pi;
    }

    @Scheduled(cron = "${findworks.shaping.worker-cron:*/1 * * * * *}")
    public void runNext() {
        var interview = interviews.claimNext();
        if (interview != null) {
            try {
                interviews.complete(interview, pi.firstQuestion(interviews.context(interview)));
            } catch (Exception error) {
                interviews.fail(interview);
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
