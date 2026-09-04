package com.findworks.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${findworks.process-mode:web}' == 'worker' or '${findworks.process-mode:web}' == 'all'")
@ConditionalOnProperty(name = "findworks.test-support", havingValue = "false", matchIfMissing = true)
class WorkerScheduler {
    private final RuntimeContainerWorker runtime;
    private final FindingsController findings;
    private final EmailDeliveryService email;
    private final LifecycleController lifecycle;

    WorkerScheduler(RuntimeContainerWorker runtime, FindingsController findings, EmailDeliveryService email,
                    LifecycleController lifecycle) {
        this.runtime = runtime;
        this.findings = findings;
        this.email = email;
        this.lifecycle = lifecycle;
    }

    @Scheduled(fixedDelayString = "${findworks.worker-poll-ms:500}")
    void poll() {
        if (!email.deliverNext() && !runtime.runNext()) findings.processExtractionNext();
    }

    @Scheduled(fixedDelayString = "${findworks.retention-poll-ms:3600000}")
    void retention() {
        lifecycle.processRetention();
    }

    @Scheduled(fixedDelayString = "${findworks.purge-poll-ms:5000}")
    void purge() {
        lifecycle.processPurge();
    }
}
