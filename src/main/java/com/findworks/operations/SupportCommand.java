package com.findworks.operations;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "findworks.process-role", havingValue = "support")
final class SupportCommand implements ApplicationRunner {

    private final OperationsRepository operations;
    private final ConfigurableApplicationContext application;
    private final String command;
    private final String kind;
    private final String id;
    private final String reasonCode;
    private final String reason;
    private final String until;
    private final CorrelationContext correlations;

    SupportCommand(OperationsRepository operations, ConfigurableApplicationContext application,
            @Value("${findworks.operations.command:}") String command,
            @Value("${findworks.operations.kind:}") String kind,
            @Value("${findworks.operations.id:}") String id,
            @Value("${findworks.operations.reason-code:}") String reasonCode,
            @Value("${findworks.operations.reason:}") String reason,
            @Value("${findworks.operations.until:}") String until,
            CorrelationContext correlations) {
        this.operations = operations;
        this.application = application;
        this.command = command;
        this.kind = kind;
        this.id = id;
        this.reasonCode = reasonCode;
        this.reason = reason;
        this.until = until;
        this.correlations = correlations;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try (var ignored = correlations.open(UUID.randomUUID())) {
            runCorrelated();
        }
        application.close();
    }

    private void runCorrelated() {
        switch (command) {
            case "status" -> {
                var value = operations.operatorStatus(reasonCode);
                System.out.printf("status firing_alerts=%d queued_jobs=%d%n",
                        value.firingAlerts(), value.queuedJobs());
            }
            case "retry-work" -> operations.retryWork(kind, uuid(), reasonCode);
            case "extend-retention" -> operations.extendRetention(uuid(), Instant.parse(until), reasonCode);
            case "revoke-access" -> operations.revokeAccess(uuid(), reasonCode);
            case "terminate-session" -> operations.terminateSession(uuid(), reasonCode);
            case "verify-deletion" -> System.out.println("deletion stage="
                    + operations.verifyDeletion(uuid(), reasonCode));
            case "verify-backup" -> System.out.println("backup state="
                    + operations.verifyBackup(uuid(), reasonCode));
            case "request-break-glass" -> System.out.println("break_glass request_id="
                    + operations.requestBreakGlass(uuid(), reasonCode, reason, Instant.parse(until)));
            case "view-break-glass" -> System.out.print(operations.viewBreakGlass(uuid(), reasonCode));
            case "revoke-break-glass" -> operations.revokeBreakGlass(uuid(), reasonCode);
            default -> throw new IllegalArgumentException("Unknown support command.");
        }
    }

    private UUID uuid() {
        return UUID.fromString(id);
    }
}
