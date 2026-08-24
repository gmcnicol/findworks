CREATE TABLE interview_completion_proposals (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    runtime_run_id uuid NOT NULL,
    proposed_revision integer NOT NULL CHECK (proposed_revision > 0),
    recap text NOT NULL CHECK (length(trim(recap)) BETWEEN 1 AND 2000),
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'continued', 'confirmed', 'withdrawn')),
    decided_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'pending' AND decided_at IS NULL)
        OR (status <> 'pending' AND decided_at IS NOT NULL)),
    UNIQUE (runtime_run_id),
    UNIQUE (id, organisation_id),
    UNIQUE (id, interview_session_id, organisation_id),
    UNIQUE (id, interview_mission_id, interview_session_id, organisation_id),
    FOREIGN KEY (runtime_run_id, interview_session_id, organisation_id)
        REFERENCES interview_runtime_runs(id, interview_session_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (interview_session_id, interview_mission_id, discovery_id, organisation_id)
        REFERENCES interview_sessions(id, interview_mission_id, discovery_id, organisation_id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX interview_completion_one_pending_idx
    ON interview_completion_proposals(interview_session_id) WHERE status = 'pending';

CREATE TABLE interview_completion_unresolved_refs (
    organisation_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    completion_proposal_id uuid NOT NULL,
    outcome_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    PRIMARY KEY (completion_proposal_id, position),
    UNIQUE (completion_proposal_id, outcome_id),
    FOREIGN KEY (
        completion_proposal_id, interview_mission_id, interview_session_id, organisation_id
    ) REFERENCES interview_completion_proposals(
        id, interview_mission_id, interview_session_id, organisation_id
    ) ON DELETE CASCADE,
    FOREIGN KEY (
        outcome_id, investigation_item_id, interview_mission_id,
        interview_session_id, organisation_id
    ) REFERENCES investigation_outcomes(
        id, investigation_item_id, interview_mission_id, interview_session_id, organisation_id
    ) ON DELETE CASCADE
);

ALTER TABLE interview_sessions
    ADD COLUMN current_completion_proposal_id uuid,
    ADD CONSTRAINT interview_sessions_completion_proposal_fk
        FOREIGN KEY (current_completion_proposal_id, id, organisation_id)
        REFERENCES interview_completion_proposals(id, interview_session_id, organisation_id),
    ADD CONSTRAINT interview_sessions_completion_pointer_check
        CHECK (current_completion_proposal_id IS NULL OR status = 'active'),
    DROP CONSTRAINT interview_sessions_control_timestamps_check,
    ADD CONSTRAINT interview_sessions_control_timestamps_check CHECK (
        (status IN ('active', 'in_progress')) = (active_started_at IS NOT NULL)
        AND (status = 'completed') = (completed_at IS NOT NULL)
        AND (status = 'ended_early') = (ended_at IS NOT NULL)
        AND (status = 'terminated') = (terminated_at IS NOT NULL)
    );

ALTER TABLE interview_application_events
    ADD COLUMN completion_proposal_id uuid,
    DROP CONSTRAINT interview_application_events_shape_check,
    ADD CONSTRAINT interview_application_events_completion_proposal_fk
        FOREIGN KEY (completion_proposal_id, interview_session_id, organisation_id)
        REFERENCES interview_completion_proposals(id, interview_session_id, organisation_id)
        ON DELETE CASCADE,
    ADD CONSTRAINT interview_application_events_shape_check CHECK (
        (event_type = 'question_ready'
            AND question_id IS NOT NULL AND completion_proposal_id IS NULL
            AND covered_count IS NOT NULL AND covered_count >= 0
            AND total_required IS NOT NULL AND total_required > 0 AND covered_count <= total_required
            AND length(trim(covered_text)) BETWEEN 1 AND 2000
            AND length(trim(current_text)) BETWEEN 1 AND 2000
            AND length(trim(remaining_text)) BETWEEN 1 AND 2000)
        OR
        (event_type = 'completion_confirmation_ready'
            AND question_id IS NULL AND completion_proposal_id IS NOT NULL
            AND covered_count IS NULL AND total_required IS NULL
            AND covered_text IS NULL AND current_text IS NULL AND remaining_text IS NULL)
        OR
        (event_type = 'runtime_failed'
            AND question_id IS NULL AND completion_proposal_id IS NULL
            AND covered_count IS NULL AND total_required IS NULL
            AND covered_text IS NULL AND current_text IS NULL AND remaining_text IS NULL)
    );

CREATE INDEX interview_completion_session_created_idx
    ON interview_completion_proposals(interview_session_id, created_at);
CREATE INDEX interview_completion_unresolved_outcome_idx
    ON interview_completion_unresolved_refs(outcome_id);

ALTER TABLE interview_completion_proposals ENABLE ROW LEVEL SECURITY;
ALTER TABLE interview_completion_proposals FORCE ROW LEVEL SECURITY;
ALTER TABLE interview_completion_unresolved_refs ENABLE ROW LEVEL SECURITY;
ALTER TABLE interview_completion_unresolved_refs FORCE ROW LEVEL SECURITY;

CREATE POLICY interview_completion_proposal_isolation ON interview_completion_proposals
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY interview_completion_unresolved_isolation ON interview_completion_unresolved_refs
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

GRANT SELECT, INSERT ON interview_completion_proposals,
    interview_completion_unresolved_refs TO findworks_application;
GRANT UPDATE (status, decided_at) ON interview_completion_proposals TO findworks_application;
GRANT UPDATE (status, completed_at, active_question_id, active_started_at, active_seconds,
    revision, current_completion_proposal_id) ON interview_sessions TO findworks_application;
GRANT SELECT, UPDATE (current_completion_proposal_id) ON interview_sessions TO findworks_application;
