package com.findworks.recovery;

import com.findworks.retention.RetentionRepository.RecoveryDeletion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class RecoveryBundle {

    record Signed(String keyId, String payload, String mac) {}

    record Ledger(int version, UUID organisationId, String sourceRestoreId,
            String sourceTimeline, Instant exportedAt, Instant highWaterAt,
            List<RecoveryDeletion> entries) {}

    record Manifest(int version, List<EvidenceExpectation> evidence,
            List<CitationExpectation> citations) {}

    record EvidenceExpectation(UUID evidenceId, String sha256) {}

    record CitationExpectation(UUID knowledgeItemVersionId, UUID evidenceId,
            int startOffset, int endOffset, String quotationSha256) {}

    record ApplyRequest(String sourceKind, String backupId, String candidateRestoreId,
            String expectedLedgerRestoreId, String expectedSourceTimeline,
            Instant incidentAuthorisedAt, Instant requestedRestoreAt, Instant achievedRestoreAt,
            Boolean encryptedAtRest, Boolean encryptedInTransit, Integer recoveryPointGapSeconds,
            Integer snapshotIntervalHours, Integer backupRetentionDays) {}
}
