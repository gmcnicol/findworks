package com.findworks.recovery;

import tools.jackson.databind.ObjectMapper;
import com.findworks.PilotProperties;
import com.findworks.recovery.RecoveryBundle.ApplyRequest;
import com.findworks.recovery.RecoveryBundle.Ledger;
import com.findworks.recovery.RecoveryBundle.Manifest;
import com.findworks.recovery.RecoveryBundle.Signed;
import com.findworks.retention.RetentionRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import org.springframework.stereotype.Service;

@Service
final class RecoveryService {

    private static final long MAX_BUNDLE_BYTES = 5L * 1024 * 1024;
    private final RecoveryRepository repository;
    private final RetentionRepository retention;
    private final RecoveryProperties properties;
    private final PilotProperties pilot;
    private final ObjectMapper json;
    private final Clock clock;

    RecoveryService(RecoveryRepository repository, RetentionRepository retention,
            RecoveryProperties properties, PilotProperties pilot, ObjectMapper json, Clock clock) {
        this.repository = repository;
        this.retention = retention;
        this.properties = properties;
        this.pilot = pilot;
        this.json = json;
        this.clock = clock;
    }

    void exportLedger(Path output, String sourceRestoreId, String sourceTimeline) {
        safeIdentifier(sourceRestoreId);
        safeIdentifier(sourceTimeline);
        var entries = repository.exportLedger();
        var highWater = clock.instant();
        write(output, new Ledger(1, pilot.organisationId(), sourceRestoreId,
                sourceTimeline, highWater, highWater, entries));
    }

    void exportManifest(Path output) {
        write(output, repository.exportManifest());
    }

    java.util.UUID apply(Path bundleFile, Path manifestFile, ApplyRequest request) {
        var started = request.incidentAuthorisedAt();
        try {
            validateRequest(request);
            var bundle = read(bundleFile, Ledger.class);
            var manifest = read(manifestFile, Manifest.class);
            if (bundle.version() != 1 || manifest.version() != 1
                    || !pilot.organisationId().equals(bundle.organisationId())
                    || !request.expectedLedgerRestoreId().equals(bundle.sourceRestoreId())
                    || !request.expectedSourceTimeline().equals(bundle.sourceTimeline())) {
                throw new RecoveryFailure("restore_identity");
            }
            var replayed = 0;
            for (var entry : bundle.entries()) {
                retention.replayDeletion(entry);
                replayed++;
            }
            var verification = repository.verify(manifest);
            if (!verification.graphVerified()) throw new RecoveryFailure("graph_invalid");
            if (!verification.accessVerified()) throw new RecoveryFailure("access_invalid");
            if (!verification.retentionVerified()) throw new RecoveryFailure("retention_invalid");
            if (!verification.deletionVerified()) throw new RecoveryFailure("deletion_invalid");
            var duration = duration(started);
            if (duration > 14_400) throw new RecoveryFailure("recovery_timeout");
            return repository.record(request, bundle.highWaterAt(), bundle.entries().size(), replayed,
                    verification, duration, "provider".equals(request.sourceKind())
                            ? "ready" : "verified_synthetic", null);
        } catch (RecoveryFailure failure) {
            repository.record(request, null, 0, 0, RecoveryRepository.Verification.failed(),
                    duration(started), "failed", failure.safeClass());
            throw failure;
        } catch (RuntimeException failure) {
            repository.record(request, null, 0, 0, RecoveryRepository.Verification.failed(),
                    duration(started), "failed", "database_unavailable");
            throw new RecoveryFailure("database_unavailable");
        }
    }

    private void validateRequest(ApplyRequest request) {
        if (!java.util.Set.of("synthetic", "provider").contains(request.sourceKind())
                || request.incidentAuthorisedAt() == null || request.requestedRestoreAt() == null
                || request.achievedRestoreAt() == null) {
            throw new RecoveryFailure("restore_identity");
        }
        safeIdentifier(request.backupId());
        safeIdentifier(request.candidateRestoreId());
        safeIdentifier(request.expectedLedgerRestoreId());
        safeIdentifier(request.expectedSourceTimeline());
        properties.requiredImageDigest();
        if ("provider".equals(request.sourceKind()) && (
                !Boolean.TRUE.equals(request.encryptedAtRest())
                || !Boolean.TRUE.equals(request.encryptedInTransit())
                || request.recoveryPointGapSeconds() == null || request.recoveryPointGapSeconds() > 300
                || request.snapshotIntervalHours() == null || request.snapshotIntervalHours() > 24
                || request.backupRetentionDays() == null || request.backupRetentionDays() > 30
                || Math.abs(Duration.between(request.requestedRestoreAt(),
                        request.achievedRestoreAt()).toSeconds()) > 300)) {
            throw new RecoveryFailure("provider_policy");
        }
    }

    private <T> T read(Path file, Class<T> type) {
        try {
            if (!Files.isRegularFile(file) || Files.size(file) <= 0 || Files.size(file) > MAX_BUNDLE_BYTES) {
                throw new RecoveryFailure("bundle_integrity");
            }
            var signed = json.readValue(Files.readString(file), Signed.class);
            if (!properties.requiredKeyId().equals(signed.keyId())) throw new RecoveryFailure("bundle_integrity");
            var payload = Base64.getUrlDecoder().decode(signed.payload());
            var supplied = Base64.getUrlDecoder().decode(signed.mac());
            if (!MessageDigest.isEqual(mac(payload), supplied)) throw new RecoveryFailure("bundle_integrity");
            return json.readValue(payload, type);
        } catch (RecoveryFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RecoveryFailure("bundle_integrity");
        }
    }

    private void write(Path file, Object value) {
        try {
            var payload = json.writeValueAsBytes(value);
            var signed = new Signed(properties.requiredKeyId(),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(payload),
                    Base64.getUrlEncoder().withoutPadding().encodeToString(mac(payload)));
            Files.writeString(file, json.writeValueAsString(signed), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (Exception failure) {
            throw new RecoveryFailure("bundle_integrity");
        }
    }

    private byte[] mac(byte[] payload) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(properties.signingKey());
            return mac.doFinal(payload);
        } catch (Exception impossible) {
            throw new IllegalStateException("Recovery bundle signing failed.");
        }
    }

    private int duration(Instant started) {
        if (started == null) return 0;
        return Math.toIntExact(Math.max(0, Math.min(Integer.MAX_VALUE,
                Duration.between(started, clock.instant()).toSeconds())));
    }

    private static void safeIdentifier(String value) {
        if (value == null || value.isBlank() || value.length() > 500
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new RecoveryFailure("restore_identity");
        }
    }
}
