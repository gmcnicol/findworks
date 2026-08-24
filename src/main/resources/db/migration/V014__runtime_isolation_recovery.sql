ALTER TABLE interview_runtime_runs
    DROP CONSTRAINT interview_runtime_runs_attempts_check,
    DROP CONSTRAINT interview_runtime_runs_error_code_check,
    DROP CONSTRAINT interview_runtime_runs_check,
    ADD COLUMN lease_owner uuid,
    ADD COLUMN model_attempts integer NOT NULL DEFAULT 0 CHECK (model_attempts BETWEEN 0 AND 3),
    ADD COLUMN process_restarts integer NOT NULL DEFAULT 0 CHECK (process_restarts BETWEEN 0 AND 1),
    ADD COLUMN runtime_version text,
    ADD CONSTRAINT interview_runtime_runs_attempts_check CHECK (attempts BETWEEN 0 AND 5),
    ADD CONSTRAINT interview_runtime_runs_error_code_check CHECK (error_code IS NULL OR error_code IN (
        'transient_model_failure', 'process_died', 'runtime_timeout',
        'invalid_runtime_output', 'stale_runtime_scope', 'runtime_unavailable'
    )),
    ADD CONSTRAINT interview_runtime_runs_lease_check CHECK (
        (status = 'running' AND lease_until IS NOT NULL AND lease_owner IS NOT NULL)
        OR (status <> 'running' AND lease_until IS NULL AND lease_owner IS NULL)
    );

DO $$
DECLARE
    constraint_name text;
BEGIN
    FOR constraint_name IN
        SELECT conname
        FROM pg_constraint
        WHERE conrelid = 'interview_application_events'::regclass AND contype = 'c'
    LOOP
        EXECUTE format('ALTER TABLE interview_application_events DROP CONSTRAINT %I', constraint_name);
    END LOOP;
END $$;

ALTER TABLE interview_application_events
    ALTER COLUMN question_id DROP NOT NULL,
    ALTER COLUMN covered_count DROP NOT NULL,
    ALTER COLUMN total_required DROP NOT NULL,
    ALTER COLUMN covered_text DROP NOT NULL,
    ALTER COLUMN current_text DROP NOT NULL,
    ALTER COLUMN remaining_text DROP NOT NULL,
    ADD CONSTRAINT interview_application_events_shape_check CHECK (
        (event_type = 'question_ready'
            AND question_id IS NOT NULL
            AND covered_count IS NOT NULL AND covered_count >= 0
            AND total_required IS NOT NULL AND total_required > 0 AND covered_count <= total_required
            AND length(trim(covered_text)) BETWEEN 1 AND 2000
            AND length(trim(current_text)) BETWEEN 1 AND 2000
            AND length(trim(remaining_text)) BETWEEN 1 AND 2000)
        OR
        (event_type = 'runtime_failed'
            AND question_id IS NULL
            AND covered_count IS NULL AND total_required IS NULL
            AND covered_text IS NULL AND current_text IS NULL AND remaining_text IS NULL)
    );

CREATE TABLE runtime_credentials (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    runtime_run_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    expected_revision integer NOT NULL CHECK (expected_revision > 0),
    token_hash char(64) NOT NULL UNIQUE,
    operation text NOT NULL CHECK (operation = 'submit_interview_turn'),
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (used_at IS NULL OR used_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (runtime_run_id, interview_session_id, organisation_id)
        REFERENCES interview_runtime_runs(id, interview_session_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (interview_session_id, interview_mission_id, discovery_id, organisation_id)
        REFERENCES interview_sessions(id, interview_mission_id, discovery_id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE runtime_checkpoints (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    runtime_run_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    expected_revision integer NOT NULL CHECK (expected_revision > 0),
    runtime_version text NOT NULL CHECK (length(runtime_version) BETWEEN 1 AND 100),
    key_id text NOT NULL CHECK (length(key_id) BETWEEN 1 AND 100),
    nonce bytea NOT NULL CHECK (octet_length(nonce) = 12),
    ciphertext bytea NOT NULL CHECK (octet_length(ciphertext) > 16),
    status text NOT NULL DEFAULT 'usable' CHECK (status IN ('usable', 'discarded')),
    discard_reason text CHECK (discard_reason IS NULL OR discard_reason IN (
        'decrypt_failed', 'mission_mismatch', 'revision_mismatch', 'runtime_mismatch'
    )),
    discarded_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'usable' AND discard_reason IS NULL AND discarded_at IS NULL)
        OR (status = 'discarded' AND discard_reason IS NOT NULL AND discarded_at IS NOT NULL)),
    UNIQUE (runtime_run_id),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (runtime_run_id, interview_session_id, organisation_id)
        REFERENCES interview_runtime_runs(id, interview_session_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (interview_session_id, interview_mission_id, discovery_id, organisation_id)
        REFERENCES interview_sessions(id, interview_mission_id, discovery_id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE interview_runtime_attempts (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    runtime_run_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    execution_attempt integer NOT NULL CHECK (execution_attempt BETWEEN 1 AND 5),
    model_attempts integer NOT NULL DEFAULT 0 CHECK (model_attempts BETWEEN 0 AND 3),
    outcome text NOT NULL CHECK (outcome IN (
        'running', 'committed', 'transient_model_failure', 'process_died', 'failed', 'cancelled'
    )),
    failure_class text CHECK (failure_class IS NULL OR failure_class IN (
        'transient_model_failure', 'process_died', 'runtime_timeout',
        'invalid_runtime_output', 'stale_runtime_scope', 'runtime_unavailable'
    )),
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    CHECK ((outcome = 'running' AND finished_at IS NULL AND failure_class IS NULL)
        OR (outcome <> 'running' AND finished_at IS NOT NULL)),
    UNIQUE (runtime_run_id, execution_attempt),
    FOREIGN KEY (runtime_run_id, interview_session_id, organisation_id)
        REFERENCES interview_runtime_runs(id, interview_session_id, organisation_id) ON DELETE CASCADE
);

CREATE INDEX runtime_credentials_run_active_idx
    ON runtime_credentials(runtime_run_id, expires_at) WHERE revoked_at IS NULL;
CREATE INDEX runtime_checkpoints_session_usable_idx
    ON runtime_checkpoints(interview_session_id, created_at DESC) WHERE status = 'usable';
CREATE INDEX runtime_attempts_run_idx
    ON interview_runtime_attempts(runtime_run_id, execution_attempt);

ALTER TABLE runtime_credentials ENABLE ROW LEVEL SECURITY;
ALTER TABLE runtime_credentials FORCE ROW LEVEL SECURITY;
ALTER TABLE runtime_checkpoints ENABLE ROW LEVEL SECURITY;
ALTER TABLE runtime_checkpoints FORCE ROW LEVEL SECURITY;
ALTER TABLE interview_runtime_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE interview_runtime_attempts FORCE ROW LEVEL SECURITY;

CREATE POLICY runtime_credential_isolation ON runtime_credentials
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY runtime_checkpoint_isolation ON runtime_checkpoints
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY runtime_attempt_isolation ON interview_runtime_attempts
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON runtime_credentials TO findworks_application;
GRANT SELECT, INSERT, UPDATE ON runtime_checkpoints TO findworks_application;
GRANT SELECT, INSERT, UPDATE ON interview_runtime_attempts TO findworks_application;
