package com.findworks.shaping;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ShapingWorker {

    private final ShapingRepository repository;
    private final PiShapingAdapter pi;

    ShapingWorker(ShapingRepository repository, PiShapingAdapter pi) {
        this.repository = repository;
        this.pi = pi;
    }

    @Scheduled(cron = "${findworks.shaping.worker-cron:*/1 * * * * *}")
    public void runNext() {
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
