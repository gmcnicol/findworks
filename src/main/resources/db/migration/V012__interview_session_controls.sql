ALTER TABLE interview_sessions
    ADD COLUMN active_started_at timestamptz,
    ADD COLUMN active_seconds bigint NOT NULL DEFAULT 0 CHECK (active_seconds >= 0),
    ADD COLUMN commitment_acknowledged_at timestamptz,
    ADD COLUMN ended_at timestamptz,
    ADD COLUMN terminated_at timestamptz,
    DROP CONSTRAINT interview_sessions_status_check,
    ADD CONSTRAINT interview_sessions_status_check CHECK (status IN (
        'not_started', 'active', 'in_progress', 'paused', 'completed', 'ended_early', 'terminated'
    ));

UPDATE interview_sessions
SET status = 'active', active_started_at = started_at
WHERE status IN ('active', 'in_progress');

ALTER TABLE interview_sessions
    ADD CONSTRAINT interview_sessions_control_timestamps_check CHECK (
        (status IN ('active', 'in_progress')) = (active_started_at IS NOT NULL)
        AND (status = 'ended_early') = (ended_at IS NOT NULL)
        AND (status = 'terminated') = (terminated_at IS NOT NULL)
    );

ALTER TABLE evidence
    ADD COLUMN revises_evidence_id uuid,
    ADD CONSTRAINT evidence_id_session_participant_org_unique
        UNIQUE (id, interview_session_id, participant_id, organisation_id),
    DROP CONSTRAINT evidence_source_type_check,
    ADD CONSTRAINT evidence_source_type_check CHECK (
        (source_type = 'legacy' AND question_id IS NULL AND revises_evidence_id IS NULL)
        OR (source_type = 'interviewee_answer' AND question_id IS NOT NULL AND revises_evidence_id IS NULL)
        OR (source_type = 'interviewee_answer_revision' AND question_id IS NOT NULL
            AND revises_evidence_id IS NOT NULL)
    ),
    ADD CONSTRAINT evidence_revision_scope_fk
        FOREIGN KEY (revises_evidence_id, interview_session_id, participant_id, organisation_id)
        REFERENCES evidence(id, interview_session_id, participant_id, organisation_id);

CREATE UNIQUE INDEX evidence_one_revision_per_predecessor_idx
    ON evidence(revises_evidence_id) WHERE revises_evidence_id IS NOT NULL;

ALTER TABLE interview_questions
    ADD COLUMN clarifies_question_id uuid,
    ADD CONSTRAINT interview_questions_not_self_clarification_check
        CHECK (clarifies_question_id IS NULL OR clarifies_question_id <> id),
    ADD CONSTRAINT interview_questions_clarification_scope_fk
        FOREIGN KEY (clarifies_question_id, interview_session_id, organisation_id)
        REFERENCES interview_questions(id, interview_session_id, organisation_id);

ALTER TABLE interview_runtime_runs
    ADD COLUMN clarifies_question_id uuid,
    ADD COLUMN cancelled_at timestamptz,
    DROP CONSTRAINT interview_runtime_runs_trigger_check,
    DROP CONSTRAINT interview_runtime_runs_evidence_check,
    DROP CONSTRAINT interview_runtime_runs_status_check,
    ADD CONSTRAINT interview_runtime_runs_trigger_check CHECK (trigger IN (
        'session_start', 'accepted_evidence', 'resume', 'clarification_request'
    )),
    ADD CONSTRAINT interview_runtime_runs_status_check CHECK (status IN (
        'queued', 'running', 'committed', 'failed', 'cancelled'
    )),
    ADD CONSTRAINT interview_runtime_runs_control_shape_check CHECK (
        (trigger IN ('session_start', 'resume') AND evidence_id IS NULL AND clarifies_question_id IS NULL)
        OR (trigger = 'accepted_evidence' AND evidence_id IS NOT NULL AND clarifies_question_id IS NULL)
        OR (trigger = 'clarification_request' AND evidence_id IS NULL AND clarifies_question_id IS NOT NULL)
    ),
    ADD CONSTRAINT interview_runtime_runs_cancellation_check CHECK (
        (status = 'cancelled') = (cancelled_at IS NOT NULL)
    ),
    ADD CONSTRAINT interview_runtime_runs_clarification_scope_fk
        FOREIGN KEY (clarifies_question_id, interview_session_id, organisation_id)
        REFERENCES interview_questions(id, interview_session_id, organisation_id);

GRANT UPDATE (
    status, started_at, active_question_id, revision, active_started_at, active_seconds,
    commitment_acknowledged_at, ended_at, terminated_at
) ON interview_sessions TO findworks_application;
