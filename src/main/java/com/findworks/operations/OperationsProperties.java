package com.findworks.operations;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("findworks.operations")
public record OperationsProperties(
        boolean trustProxyCorrelation,
        Duration oldJobThreshold,
        double minimumDiskFreeRatio,
        UUID operatorId,
        String breakGlassKeyId,
        String breakGlassKey) {

    public OperationsProperties {
        oldJobThreshold = oldJobThreshold == null ? Duration.ofMinutes(5) : oldJobThreshold;
        minimumDiskFreeRatio = minimumDiskFreeRatio == 0 ? 0.1 : minimumDiskFreeRatio;
        if (oldJobThreshold.isNegative() || oldJobThreshold.isZero()
                || minimumDiskFreeRatio < 0.01 || minimumDiskFreeRatio > 0.5) {
            throw new IllegalArgumentException("Operational thresholds are invalid.");
        }
    }

    public UUID requiredOperatorId() {
        if (operatorId == null) throw new IllegalStateException("Support operator identity is not configured.");
        return operatorId;
    }
}
