package com.findworks.operations;

import java.util.List;
import java.util.UUID;

@FunctionalInterface
public interface OperationalSignalSource {
    List<Signal> current();

    record Signal(OperationalTelemetry.AlertKind kind, OperationalTelemetry.SafeError error, UUID resourceId) {}
}
