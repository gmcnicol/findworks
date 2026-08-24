package com.findworks.operations;

import com.findworks.operations.OperationalSignalSource.Signal;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${findworks.process-role:local}' == 'local' || '${findworks.process-role:local}' == 'worker'")
public final class OperationalHealthMonitor {

    private final OperationsRepository repository;
    private final OperationalSignalSource external;
    private final AlertReceiver receiver;
    private final OperationalTelemetry telemetry;
    private final OperationsProperties properties;
    private final CorrelationContext correlations;

    OperationalHealthMonitor(OperationsRepository repository, OperationalSignalSource external,
            AlertReceiver receiver, OperationalTelemetry telemetry, OperationsProperties properties,
            CorrelationContext correlations) {
        this.repository = repository;
        this.external = external;
        this.receiver = receiver;
        this.telemetry = telemetry;
        this.properties = properties;
        this.correlations = correlations;
    }

    @Scheduled(cron = "${findworks.operations.monitor-cron:0 * * * * *}")
    public void run() {
        try (var ignored = correlations.open(java.util.UUID.randomUUID())) {
            runCorrelated();
        }
    }

    private void runCorrelated() {
        var signals = new ArrayList<>(repository.currentDatabaseSignals());
        try {
            signals.addAll(external.current());
        } catch (RuntimeException unavailable) {
            signals.add(new Signal(OperationalTelemetry.AlertKind.TELEMETRY_GAP,
                    OperationalTelemetry.SafeError.UNAVAILABLE, null));
        }
        diskSignal().ifPresent(signals::add);
        for (var transition : repository.reconcile(signals)) {
            telemetry.alert(transition.kind(), transition.firing(), transition.error(), transition.resourceId());
            try {
                receiver.publish(transition);
            } catch (RuntimeException ignored) {
                // Alert delivery cannot block domain or durable alert state.
            }
        }
    }

    private java.util.Optional<Signal> diskSignal() {
        try {
            FileStore disk = Files.getFileStore(Path.of("/tmp"));
            var ratio = (double) disk.getUsableSpace() / disk.getTotalSpace();
            if (ratio < properties.minimumDiskFreeRatio()) {
                return java.util.Optional.of(new Signal(OperationalTelemetry.AlertKind.DISK_PRESSURE,
                        OperationalTelemetry.SafeError.CAPACITY, null));
            }
        } catch (Exception ignored) {
            return java.util.Optional.of(new Signal(OperationalTelemetry.AlertKind.DISK_PRESSURE,
                    OperationalTelemetry.SafeError.UNAVAILABLE, null));
        }
        return java.util.Optional.empty();
    }
}
