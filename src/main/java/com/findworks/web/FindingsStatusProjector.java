package com.findworks.web;

import org.springframework.stereotype.Component;

/** One presentation policy for extraction and Findings Package status on every Investigator surface. */
@Component
public class FindingsStatusProjector {
    public Status project(String sessionState, String extractionState, String packageState) {
        if (!"COMPLETED".equals(sessionState)) {
            if ("RUNTIME_FAILED".equals(sessionState)) {
                return new Status("Interview needs recovery · transcript saved", "attention", "Review recovery", true);
            }
            if ("NOT_STARTED".equals(sessionState) || "ACTIVE".equals(sessionState)
                    || "PAUSED".equals(sessionState) || "AWAITING_CONFIRMATION".equals(sessionState)) {
                return new Status("Interview in progress · transcript saved", "active", "View transcript", false);
            }
            return new Status("Interview status needs attention · transcript saved", "attention", "View transcript", true);
        }
        return project(extractionState, packageState);
    }

    public Status project(String extractionState, String packageState) {
        if ("FAILED".equals(extractionState)) {
            return new Status("Extraction failed · Retry is safe", "attention", "Retry extraction", true);
        }
        if ("PENDING".equals(extractionState)) {
            return new Status("Extraction pending", "waiting", "View progress", false);
        }
        if (!"READY".equals(extractionState)) {
            return new Status("Extraction status needs attention", "attention", "Open findings", true);
        }
        if ("READY".equals(packageState)) {
            return new Status("Ready for review", "attention", "Review findings", true);
        }
        if ("ACCEPTED".equals(packageState)) {
            return new Status("Accepted", "complete", "Open findings", false);
        }
        if ("REJECTED".equals(packageState)) {
            return new Status("Follow-up required", "attention", "Review follow-up", true);
        }
        return new Status("Findings status needs attention", "attention", "Open findings", true);
    }

    public record Status(String label, String tone, String action, boolean needsAttention) {}
}
