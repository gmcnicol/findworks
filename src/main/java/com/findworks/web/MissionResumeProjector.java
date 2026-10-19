package com.findworks.web;

import java.util.UUID;

import org.springframework.stereotype.Component;

/** The single lifecycle-to-next-action policy used by Investigator re-entry surfaces. */
@Component
public class MissionResumeProjector {
    private static final String[] CHECKPOINTS = {
            "purpose-heading", "interviewee-heading", "items-heading", "context-heading", "completion-heading"
    };

    public ResumeAction project(Snapshot snapshot) {
        String missionHref = "/app/missions/" + snapshot.missionId() + "/review";
        if ("DRAFT".equals(snapshot.missionState())) {
            int checkpoint = snapshot.nextReviewCheckpoint();
            if (checkpoint > 5) return action("Ready for approval", "attention", "Approve Mission",
                    missionHref + "#approval-decision", true, false);
            if (checkpoint < 1) throw new IllegalArgumentException("Invalid review checkpoint");
            return action("Draft needs review", "draft", checkpoint == 1 ? "Review Mission" : "Continue Mission review",
                    missionHref + "#" + CHECKPOINTS[checkpoint - 1], true, false);
        }
        if (!"APPROVED".equals(snapshot.missionState())) {
            return action("Mission needs review", "attention", "Open Mission", missionHref, true, false);
        }
        if (snapshot.sessionId() == null) return action("Approved, ready to invite", "attention", "Invite interviewee",
                missionHref + "#invite-heading", true, false);

        String sessionHref = "/app/interviews/" + snapshot.sessionId();
        if (java.util.Set.of("ACTIVE", "PAUSED", "AWAITING_CONFIRMATION", "RUNTIME_FAILED").contains(snapshot.sessionState())
                && !snapshot.browserAccessActive() && !snapshot.activeInvitation() && !snapshot.pendingInvitation()) {
            return action("Interview access needs reissue", "attention", "Reissue access", sessionHref, true, true);
        }
        return switch (snapshot.sessionState()) {
            case "NOT_STARTED" -> {
                if (snapshot.browserAccessActive()) yield action("Ready to begin", "waiting", "View interview status", sessionHref, false, false);
                if (snapshot.activeInvitation()) yield action("Invitation active", "waiting", "View invitation status", sessionHref, false, false);
                if (snapshot.pendingInvitation()) yield action("Invitation sending", "waiting", "View invitation status", sessionHref, false, false);
                yield action("Invitation needs reissue", "attention", "Reissue invitation", missionHref + "#invite-heading", true, false);
            }
            case "ACTIVE", "AWAITING_CONFIRMATION" -> action("Interview in progress", "active", "View interview status", sessionHref, false, true);
            case "PAUSED" -> action("Interview paused", "waiting", "View interview status", sessionHref, false, true);
            case "RUNTIME_FAILED" -> action("Interview needs recovery", "attention", "Review recovery", sessionHref, true, true);
            case "TERMINATED" -> action("Interview terminated", "complete", "View interview status", sessionHref, false, false);
            case "COMPLETED" -> findings(snapshot);
            default -> action("Interview needs attention", "attention", "View interview status", sessionHref, true, false);
        };
    }

    private ResumeAction findings(Snapshot snapshot) {
        String href = "/app/findings/" + snapshot.sessionId();
        if ("FAILED".equals(snapshot.extractionState())) return action("Findings extraction failed", "attention", "Retry extraction", href, true, false);
        if (!"READY".equals(snapshot.extractionState())) return action("Findings processing", "waiting", "View progress", href, false, false);
        if ("ACCEPTED".equals(snapshot.packageState())) return action("Findings accepted", "complete", "Open findings", href, false, false);
        if ("REJECTED".equals(snapshot.packageState())) return action("Follow-up required", "attention", "Review follow-up", href, true, false);
        return action("Findings ready to review", "attention", "Review findings", href, true, false);
    }

    private ResumeAction action(String status, String tone, String label, String href, boolean attention, boolean active) {
        return new ResumeAction(status, tone, label, href, attention, active);
    }

    public record Snapshot(UUID missionId, String missionState, int nextReviewCheckpoint, UUID sessionId,
                           String sessionState, String extractionState, String packageState,
                           boolean activeInvitation, boolean pendingInvitation, boolean browserAccessActive) {}

    public record ResumeAction(String status, String statusTone, String label, String href,
                               boolean needsAttention, boolean interviewActive) {}
}
