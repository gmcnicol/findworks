package com.findworks.operations;

import java.util.UUID;

@FunctionalInterface
public interface BackupVerifier {
    Result verify(UUID backupId);

    record Result(String state) {
        public Result {
            if (!java.util.Set.of("verified", "failed", "stale", "unavailable").contains(state)) {
                throw new IllegalArgumentException("Backup state is invalid.");
            }
        }
    }
}
