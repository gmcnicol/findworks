package com.findworks.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MissionResumeProjectorTest {

    private final MissionResumeProjector projector = new MissionResumeProjector();
    private final UUID missionId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    @Test
    void draft_resumes_at_first_incomplete_checkpoint() {
        var action = projector.project(snapshot("DRAFT", 3, null, null, null, null, false));

        assertThat(action.label()).isEqualTo("Continue Mission review");
        assertThat(action.href()).isEqualTo("/app/missions/" + missionId + "/review#items-heading");
        assertThat(action.needsAttention()).isTrue();
    }

    @Test
    void active_interview_resumes_at_investigator_status_not_mission_review() {
        var action = projector.project(snapshot("APPROVED", 6, sessionId, "ACTIVE", "PENDING", null, false));

        assertThat(action.status()).isEqualTo("Interview in progress");
        assertThat(action.href()).isEqualTo("/app/interviews/" + sessionId);
        assertThat(action.interviewActive()).isTrue();
    }

    @Test
    void ready_findings_resume_at_findings_review() {
        var action = projector.project(snapshot("APPROVED", 6, sessionId, "COMPLETED", "READY", "READY", false));

        assertThat(action.label()).isEqualTo("Review findings");
        assertThat(action.href()).isEqualTo("/app/findings/" + sessionId);
        assertThat(action.needsAttention()).isTrue();
    }

    @Test
    void all_reviewed_checkpoints_lead_to_approval_not_checkpoint_five() {
        var action = projector.project(snapshot("DRAFT", 6, null, null, null, null, false));
        assertThat(action.label()).isEqualTo("Approve Mission");
        assertThat(action.href()).endsWith("#approval-decision");
    }

    @Test
    void redeemed_invitation_is_ready_to_begin_not_ready_to_reissue() {
        var action = projector.project(snapshot("APPROVED", 6, sessionId, "NOT_STARTED", "PENDING", null, false));
        assertThat(action.status()).isEqualTo("Ready to begin");
        assertThat(action.needsAttention()).isFalse();
    }

    @Test
    void pending_delivery_does_not_offer_another_invitation() {
        var action = projector.project(new MissionResumeProjector.Snapshot(missionId, "APPROVED", 6,
                sessionId, "NOT_STARTED", "PENDING", null, false, true, false));
        assertThat(action.status()).isEqualTo("Invitation sending");
        assertThat(action.needsAttention()).isFalse();
    }

    @Test
    void unknown_session_state_fails_to_attention_not_processing_or_completion() {
        var action = projector.project(snapshot("APPROVED", 6, sessionId, "UNEXPECTED", "READY", "ACCEPTED", false));
        assertThat(action.status()).isEqualTo("Interview needs attention");
        assertThat(action.href()).isEqualTo("/app/interviews/" + sessionId);
    }

    @Test
    void rejected_findings_keep_follow_up_visible() {
        var action = projector.project(snapshot("APPROVED", 6, sessionId, "COMPLETED", "READY", "REJECTED", false));
        assertThat(action.label()).isEqualTo("Review follow-up");
        assertThat(action.needsAttention()).isTrue();
    }

    private MissionResumeProjector.Snapshot snapshot(String missionState, int checkpoint, UUID session,
                                                      String sessionState, String extractionState,
                                                      String packageState, boolean activeInvitation) {
        return new MissionResumeProjector.Snapshot(missionId, missionState, checkpoint, session, sessionState,
                extractionState, packageState, activeInvitation, false, session != null);
    }
}
