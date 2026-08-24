package com.findworks.deployment;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${findworks.process-role:local}' == 'local' || '${findworks.process-role:local}' == 'worker'")
public final class WorkerExecution {

    private final Duration heartbeatInterval;
    private final boolean synchronous;
    private final ScheduledExecutorService pi = Executors.newScheduledThreadPool(
            2, Thread.ofVirtual().name("findworks-pi-", 0).factory());
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(
            4, Thread.ofVirtual().name("findworks-heartbeat-", 0).factory());

    WorkerExecution(@Value("${findworks.worker.heartbeat-interval:PT30S}") Duration heartbeatInterval,
            @Value("${findworks.process-role:local}") String role) {
        if (heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()
                || heartbeatInterval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("Worker heartbeat interval must be between zero and one minute.");
        }
        this.heartbeatInterval = heartbeatInterval;
        this.synchronous = "local".equals(role);
    }

    public void submitPi(Runnable task) {
        if (synchronous) {
            task.run();
        } else {
            pi.execute(task);
        }
    }

    public <T> T withHeartbeat(BooleanSupplier heartbeat, Callable<T> task) throws Exception {
        var leaseLost = new AtomicBoolean();
        Future<?> pulse = heartbeats.scheduleAtFixedRate(() -> {
            try {
                if (!heartbeat.getAsBoolean()) {
                    leaseLost.set(true);
                }
            } catch (RuntimeException failure) {
                leaseLost.set(true);
            }
        }, heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
        try {
            var result = task.call();
            if (leaseLost.get()) {
                throw new LeaseLost();
            }
            return result;
        } finally {
            pulse.cancel(true);
        }
    }

    @PreDestroy
    void close() {
        pi.shutdown();
        heartbeats.shutdownNow();
    }

    public static final class LeaseLost extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
