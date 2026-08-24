package com.findworks.recovery;

import com.findworks.operations.CorrelationContext;
import com.findworks.recovery.RecoveryBundle.ApplyRequest;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnExpression("'${findworks.process-role:local}' == 'recovery'"
        + " && '${findworks.recovery.run-command:false}' == 'true'")
final class RecoveryCommand implements ApplicationRunner {

    private final RecoveryService recovery;
    private final CorrelationContext correlations;
    private final ConfigurableApplicationContext application;
    private final String command;
    private final String output;
    private final String bundle;
    private final String manifest;
    private final String sourceKind;
    private final String backupId;
    private final String candidateRestoreId;
    private final String ledgerRestoreId;
    private final String timeline;
    private final String incidentAt;
    private final String requestedAt;
    private final String achievedAt;
    private final Boolean encryptedAtRest;
    private final Boolean encryptedInTransit;
    private final Integer recoveryPointGapSeconds;
    private final Integer snapshotIntervalHours;
    private final Integer backupRetentionDays;

    RecoveryCommand(RecoveryService recovery, CorrelationContext correlations,
            ConfigurableApplicationContext application,
            @Value("${findworks.recovery.command:}") String command,
            @Value("${findworks.recovery.output:}") String output,
            @Value("${findworks.recovery.bundle:}") String bundle,
            @Value("${findworks.recovery.manifest:}") String manifest,
            @Value("${findworks.recovery.source-kind:synthetic}") String sourceKind,
            @Value("${findworks.recovery.backup-id:}") String backupId,
            @Value("${findworks.recovery.candidate-restore-id:}") String candidateRestoreId,
            @Value("${findworks.recovery.ledger-restore-id:}") String ledgerRestoreId,
            @Value("${findworks.recovery.source-timeline:}") String timeline,
            @Value("${findworks.recovery.incident-authorised-at:}") String incidentAt,
            @Value("${findworks.recovery.requested-restore-at:}") String requestedAt,
            @Value("${findworks.recovery.achieved-restore-at:}") String achievedAt,
            @Value("${findworks.recovery.encrypted-at-rest:#{null}}") Boolean encryptedAtRest,
            @Value("${findworks.recovery.encrypted-in-transit:#{null}}") Boolean encryptedInTransit,
            @Value("${findworks.recovery.recovery-point-gap-seconds:#{null}}") Integer recoveryPointGapSeconds,
            @Value("${findworks.recovery.snapshot-interval-hours:#{null}}") Integer snapshotIntervalHours,
            @Value("${findworks.recovery.backup-retention-days:#{null}}") Integer backupRetentionDays) {
        this.recovery = recovery;
        this.correlations = correlations;
        this.application = application;
        this.command = command;
        this.output = output;
        this.bundle = bundle;
        this.manifest = manifest;
        this.sourceKind = sourceKind;
        this.backupId = backupId;
        this.candidateRestoreId = candidateRestoreId;
        this.ledgerRestoreId = ledgerRestoreId;
        this.timeline = timeline;
        this.incidentAt = incidentAt;
        this.requestedAt = requestedAt;
        this.achievedAt = achievedAt;
        this.encryptedAtRest = encryptedAtRest;
        this.encryptedInTransit = encryptedInTransit;
        this.recoveryPointGapSeconds = recoveryPointGapSeconds;
        this.snapshotIntervalHours = snapshotIntervalHours;
        this.backupRetentionDays = backupRetentionDays;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        try (var ignored = correlations.open(UUID.randomUUID())) {
            switch (command) {
                case "export-ledger" -> recovery.exportLedger(Path.of(output), ledgerRestoreId, timeline);
                case "export-manifest" -> recovery.exportManifest(Path.of(output));
                case "apply" -> System.out.println("recovery drill_id=" + recovery.apply(
                        Path.of(bundle), Path.of(manifest), new ApplyRequest(sourceKind, backupId,
                                candidateRestoreId, ledgerRestoreId, timeline, Instant.parse(incidentAt),
                                Instant.parse(requestedAt), Instant.parse(achievedAt), encryptedAtRest,
                                encryptedInTransit, recoveryPointGapSeconds, snapshotIntervalHours,
                                backupRetentionDays)));
                default -> throw new IllegalArgumentException("Unknown recovery command.");
            }
        }
        application.close();
    }
}
