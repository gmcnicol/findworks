package com.findworks.recovery;

final class RecoveryFailure extends RuntimeException {
    private final String safeClass;

    RecoveryFailure(String safeClass) {
        super("Recovery drill failed: " + safeClass);
        this.safeClass = safeClass;
    }

    String safeClass() {
        return safeClass;
    }
}
