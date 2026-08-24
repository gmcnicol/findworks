package com.findworks.operations;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
public final class CorrelationContext {

    static final String KEY = "correlation_id";

    public UUID current() {
        var value = MDC.get(KEY);
        return value == null ? UUID.randomUUID() : UUID.fromString(value);
    }

    public Scope open(UUID correlationId) {
        var previous = MDC.get(KEY);
        MDC.put(KEY, correlationId.toString());
        return () -> {
            if (previous == null) {
                MDC.remove(KEY);
            } else {
                MDC.put(KEY, previous);
            }
        };
    }

    public interface Scope extends AutoCloseable {
        @Override
        void close();
    }
}
