package com.findworks.runtime;

import com.findworks.interview.InterviewRuntimeRepository;
import java.time.Instant;

public interface InterviewTurnRunner {

    String runtimeVersion();

    Result run(Request request) throws RuntimeFailure;

    record Request(InterviewRuntimeRepository.Context context, byte[] checkpoint,
            String credential, Instant credentialExpiresAt) {
        public Request {
            checkpoint = checkpoint == null ? null : checkpoint.clone();
        }

        @Override
        public byte[] checkpoint() {
            return checkpoint == null ? null : checkpoint.clone();
        }
    }

    record Result(InterviewRuntimeRepository.Submission submission, byte[] checkpoint,
            String runtimeVersion, int modelAttempts, String credential) {
        public Result {
            checkpoint = checkpoint == null ? null : checkpoint.clone();
            if (modelAttempts < 1 || modelAttempts > 3) {
                throw new IllegalArgumentException("Model attempt count is invalid.");
            }
        }

        @Override
        public byte[] checkpoint() {
            return checkpoint == null ? null : checkpoint.clone();
        }
    }
}
