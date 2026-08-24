package com.findworks.operations;

import java.time.Instant;
import java.util.UUID;

@FunctionalInterface
public interface AlertReceiver {
    void publish(Event event);

    record Event(UUID alertId, OperationalTelemetry.AlertKind kind, boolean firing,
            OperationalTelemetry.SafeError error, UUID resourceId, Instant observedAt) {}
}
