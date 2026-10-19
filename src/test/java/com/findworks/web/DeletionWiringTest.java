package com.findworks.web;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;

class DeletionWiringTest {

    @Test
    void worker_schedules_only_non_deletion_work() {
        var scheduledMethods = Arrays.stream(WorkerScheduler.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Scheduled.class))
                .map(method -> method.getName())
                .toList();

        assertThat(scheduledMethods).containsExactly("poll");
    }

    @Test
    void startup_has_no_deletion_ledger_reapplication_listener() {
        var startupListeners = Arrays.stream(LifecycleController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(EventListener.class))
                .toList();

        assertThat(startupListeners).isEmpty();
    }
}
