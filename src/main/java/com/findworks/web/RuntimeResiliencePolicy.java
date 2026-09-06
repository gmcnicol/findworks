package com.findworks.web;

import java.util.Set;
import java.util.UUID;

final class RuntimeResiliencePolicy {
    private static final Set<String> RETRYABLE = Set.of(
            "MODEL_FAILURE", "RUNTIME_TIMEOUT", "RUNTIME_EXIT", "RUNTIME_START_FAILED", "NO_SEMANTIC_COMMIT");

    private RuntimeResiliencePolicy() {}

    static int backoffSeconds(int completedAttempts, UUID runId) {
        var base = switch (completedAttempts) {
            case 1 -> 2;
            case 2 -> 8;
            default -> 30;
        };
        return base + Math.floorMod(runId.hashCode(), 3);
    }

    static boolean retryable(String errorCode) {
        return RETRYABLE.contains(errorCode);
    }
}
