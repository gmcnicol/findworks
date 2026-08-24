package com.findworks.retention;

import com.findworks.PilotProperties;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "findworks.retention.command")
final class RetentionCommand implements ApplicationRunner {

    private final RetentionRepository retention;
    private final RetentionWorker worker;
    private final PilotProperties pilot;
    private final ConfigurableApplicationContext context;
    private final String command;
    private final String discoveryId;
    private final String missionId;
    private final String sessionId;
    private final String until;

    RetentionCommand(RetentionRepository retention, RetentionWorker worker, PilotProperties pilot,
            ConfigurableApplicationContext context,
            @Value("${findworks.retention.command}") String command,
            @Value("${findworks.retention.discovery-id:}") String discoveryId,
            @Value("${findworks.retention.mission-id:}") String missionId,
            @Value("${findworks.retention.session-id:}") String sessionId,
            @Value("${findworks.retention.until:}") String until) {
        this.retention = retention;
        this.worker = worker;
        this.pilot = pilot;
        this.context = context;
        this.command = command;
        this.discoveryId = discoveryId;
        this.missionId = missionId;
        this.sessionId = sessionId;
        this.until = until;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        switch (command) {
            case "extend" -> retention.extend(uuid(discoveryId), Instant.parse(until), pilot.investigatorEmail());
            case "delete-discovery" -> retention.requestDiscoveryDeletion(
                    uuid(discoveryId), pilot.investigatorEmail());
            case "delete-session" -> retention.requestSessionDeletion(
                    uuid(missionId), uuid(sessionId), pilot.investigatorEmail());
            case "schedule" -> retention.schedule();
            case "send-warning" -> worker.sendWarning();
            case "purge" -> worker.purgeNext();
            case "prune-audit" -> retention.pruneAudit();
            default -> throw new IllegalArgumentException("Unknown retention command.");
        }
        context.close();
    }

    private static UUID uuid(String value) {
        return UUID.fromString(value);
    }
}
