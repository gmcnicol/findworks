package com.findworks.recovery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
final class RecoveryTrafficGate implements ApplicationRunner {

    private final RecoveryRepository recovery;
    private final String role;

    RecoveryTrafficGate(RecoveryRepository recovery,
            @Value("${findworks.process-role:local}") String role) {
        this.recovery = recovery;
        this.role = role;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        if ("web".equals(role) || "worker".equals(role)) recovery.requireTrafficGate();
    }
}
