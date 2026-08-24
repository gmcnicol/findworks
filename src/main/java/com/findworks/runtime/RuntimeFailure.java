package com.findworks.runtime;

public final class RuntimeFailure extends Exception {

    public enum Kind {
        TRANSIENT_MODEL("transient_model_failure"),
        PROCESS_DIED("process_died"),
        TIMEOUT("runtime_timeout"),
        INVALID_OUTPUT("invalid_runtime_output"),
        STALE_SCOPE("stale_runtime_scope"),
        UNAVAILABLE("runtime_unavailable");

        private final String code;

        Kind(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private final Kind kind;
    private final int modelAttempts;
    private final byte[] checkpoint;

    public RuntimeFailure(Kind kind, int modelAttempts) {
        this(kind, modelAttempts, null);
    }

    public RuntimeFailure(Kind kind, int modelAttempts, byte[] checkpoint) {
        super(kind.code());
        this.kind = kind;
        this.modelAttempts = modelAttempts;
        this.checkpoint = checkpoint == null ? null : checkpoint.clone();
    }

    public Kind kind() {
        return kind;
    }

    public int modelAttempts() {
        return modelAttempts;
    }

    public byte[] checkpoint() {
        return checkpoint == null ? null : checkpoint.clone();
    }
}
