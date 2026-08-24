ALTER TABLE interview_sessions
    ADD CONSTRAINT interview_sessions_id_mission_discovery_org_unique
        UNIQUE (id, interview_mission_id, discovery_id, organisation_id);

CREATE TABLE interview_runtime_runs (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    trigger text NOT NULL CHECK (trigger IN ('session_start')),
    expected_revision integer NOT NULL CHECK (expected_revision > 0),
    status text NOT NULL DEFAULT 'queued'
        CHECK (status IN ('queued', 'running', 'committed', 'failed')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    error_code text CHECK (error_code IS NULL OR error_code = 'runtime_error'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'running' AND lease_until IS NOT NULL)
        OR (status <> 'running' AND lease_until IS NULL)),
    UNIQUE (interview_session_id, trigger, expected_revision),
    UNIQUE (id, organisation_id),
    UNIQUE (id, interview_session_id, organisation_id),
    FOREIGN KEY (interview_session_id, interview_mission_id, discovery_id, organisation_id)
        REFERENCES interview_sessions(id, interview_mission_id, discovery_id, organisation_id)
        ON DELETE CASCADE,
    FOREIGN KEY (interview_mission_id, discovery_id, organisation_id)
        REFERENCES interview_missions(id, discovery_id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE interview_questions (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    sequence integer NOT NULL CHECK (sequence > 0),
    question text NOT NULL CHECK (length(trim(question)) BETWEEN 1 AND 2000),
    human_context text CHECK (human_context IS NULL OR length(trim(human_context)) BETWEEN 1 AND 2000),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (interview_session_id, sequence),
    UNIQUE (id, organisation_id),
    UNIQUE (id, interview_session_id, organisation_id),
    FOREIGN KEY (interview_session_id, interview_mission_id, discovery_id, organisation_id)
        REFERENCES interview_sessions(id, interview_mission_id, discovery_id, organisation_id)
        ON DELETE CASCADE,
    FOREIGN KEY (investigation_item_id, interview_mission_id, organisation_id)
        REFERENCES investigation_items(id, interview_mission_id, organisation_id) ON DELETE CASCADE
);

ALTER TABLE interview_sessions ADD COLUMN active_question_id uuid;

ALTER TABLE interview_sessions
    ADD CONSTRAINT interview_sessions_active_question_fk
        FOREIGN KEY (active_question_id, id, organisation_id)
        REFERENCES interview_questions(id, interview_session_id, organisation_id)
        ON DELETE SET NULL (active_question_id);

CREATE TABLE interview_application_events (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    runtime_run_id uuid NOT NULL,
    question_id uuid NOT NULL,
    event_type text NOT NULL CHECK (event_type = 'question_ready'),
    covered_count integer NOT NULL CHECK (covered_count >= 0),
    total_required integer NOT NULL CHECK (total_required > 0 AND covered_count <= total_required),
    covered_text text NOT NULL CHECK (length(trim(covered_text)) BETWEEN 1 AND 2000),
    current_text text NOT NULL CHECK (length(trim(current_text)) BETWEEN 1 AND 2000),
    remaining_text text NOT NULL CHECK (length(trim(remaining_text)) BETWEEN 1 AND 2000),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (runtime_run_id),
    UNIQUE (question_id),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (runtime_run_id, interview_session_id, organisation_id)
        REFERENCES interview_runtime_runs(id, interview_session_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (question_id, interview_session_id, organisation_id)
        REFERENCES interview_questions(id, interview_session_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (discovery_id, organisation_id)
        REFERENCES discoveries(id, organisation_id) ON DELETE CASCADE
);

CREATE INDEX interview_runtime_runs_ready_idx
    ON interview_runtime_runs(available_at, created_at) WHERE status IN ('queued', 'running');
CREATE INDEX interview_questions_session_sequence_idx
    ON interview_questions(interview_session_id, sequence);
CREATE INDEX interview_events_session_created_idx
    ON interview_application_events(interview_session_id, created_at);

ALTER TABLE interview_runtime_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE interview_runtime_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE interview_questions ENABLE ROW LEVEL SECURITY;
ALTER TABLE interview_questions FORCE ROW LEVEL SECURITY;
ALTER TABLE interview_application_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE interview_application_events FORCE ROW LEVEL SECURITY;

CREATE POLICY interview_run_isolation ON interview_runtime_runs
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY interview_question_isolation ON interview_questions
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY interview_event_isolation ON interview_application_events
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

INSERT INTO interview_runtime_runs (
    id, organisation_id, discovery_id, interview_session_id, interview_mission_id,
    trigger, expected_revision
)
SELECT gen_random_uuid(), organisation_id, discovery_id, id, interview_mission_id,
       'session_start', revision
FROM interview_sessions
WHERE status = 'active' AND revision > 0 AND active_question_id IS NULL
ON CONFLICT (interview_session_id, trigger, expected_revision) DO NOTHING;

GRANT SELECT, INSERT, UPDATE ON interview_runtime_runs TO findworks_application;
GRANT SELECT, INSERT ON interview_questions TO findworks_application;
GRANT SELECT, INSERT ON interview_application_events TO findworks_application;
