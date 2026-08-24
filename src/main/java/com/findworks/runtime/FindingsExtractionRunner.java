package com.findworks.runtime;

import com.findworks.interview.FindingsRepository;
import java.time.Instant;

public interface FindingsExtractionRunner {

    String runtimeVersion();

    Result run(Request request) throws RuntimeFailure;

    record Request(FindingsRepository.Context context, String credential, Instant credentialExpiresAt) {}

    record Result(FindingsRepository.Submission submission, String runtimeVersion,
            int modelAttempts, String credential) {
        public Result {
            if (modelAttempts < 1 || modelAttempts > 3) {
                throw new IllegalArgumentException("Model attempt count is invalid.");
            }
        }
    }
}
