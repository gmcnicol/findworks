package com.findworks.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeResiliencePolicyTest {
    private static final UUID RUN = UUID.fromString("11111111-1111-4111-8111-111111111111");

    @Test
    void retries_back_off_with_bounded_stable_jitter() {
        var first = RuntimeResiliencePolicy.backoffSeconds(1, RUN);
        var second = RuntimeResiliencePolicy.backoffSeconds(2, RUN);

        assertThat(first).isBetween(2, 4);
        assertThat(second).isBetween(8, 10);
        assertThat(second).isGreaterThan(first);
        assertThat(RuntimeResiliencePolicy.backoffSeconds(2, RUN)).isEqualTo(second);
    }

    @Test
    void classifies_only_provider_failures_as_retryable() {
        assertThat(RuntimeResiliencePolicy.retryable("MODEL_FAILURE")).isTrue();
        assertThat(RuntimeResiliencePolicy.retryable("RUNTIME_TIMEOUT")).isTrue();
        assertThat(RuntimeResiliencePolicy.retryable("NO_SEMANTIC_COMMIT")).isTrue();
        assertThat(RuntimeResiliencePolicy.retryable("RUNTIME_CONFIGURATION")).isFalse();
    }
}
