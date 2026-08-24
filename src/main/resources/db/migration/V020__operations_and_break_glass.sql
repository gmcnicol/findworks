ALTER TABLE audit_records DROP CONSTRAINT audit_records_actor_kind_check;
ALTER TABLE audit_records ADD CONSTRAINT audit_records_actor_kind_check
    CHECK (actor_kind IN ('investigator', 'operator', 'system'));

ALTER TABLE shaping_runtime_work
    ADD COLUMN origin_correlation_id uuid NOT NULL DEFAULT coalesce(
        nullif(current_setting('findworks.correlation_id', true), '')::uuid, gen_random_uuid()),
    ADD COLUMN execution_correlation_id uuid;
ALTER TABLE interview_runtime_runs
    ADD COLUMN origin_correlation_id uuid NOT NULL DEFAULT coalesce(
        nullif(current_setting('findworks.correlation_id', true), '')::uuid, gen_random_uuid()),
    ADD COLUMN execution_correlation_id uuid;
ALTER TABLE invitation_delivery_jobs
    ADD COLUMN origin_correlation_id uuid NOT NULL DEFAULT coalesce(
        nullif(current_setting('findworks.correlation_id', true), '')::uuid, gen_random_uuid()),
    ADD COLUMN execution_correlation_id uuid;
ALTER TABLE retention_warnings
    ADD COLUMN origin_correlation_id uuid NOT NULL DEFAULT coalesce(
        nullif(current_setting('findworks.correlation_id', true), '')::uuid, gen_random_uuid()),
    ADD COLUMN execution_correlation_id uuid;
ALTER TABLE deletion_ledger
    ADD COLUMN origin_correlation_id uuid NOT NULL DEFAULT coalesce(
        nullif(current_setting('findworks.correlation_id', true), '')::uuid, gen_random_uuid()),
    ADD COLUMN execution_correlation_id uuid;

CREATE TABLE operational_alerts (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL REFERENCES organisations(id),
    alert_kind text NOT NULL CHECK (alert_kind IN (
        'web_outage', 'worker_outage', 'database_outage', 'old_job',
        'runtime_failure', 'email_failure', 'disk_pressure', 'backup_failed',
        'backup_stale', 'purge_overdue', 'telemetry_gap'
    )),
    affected_resource_id uuid,
    status text NOT NULL CHECK (status IN ('firing', 'resolved')),
    safe_error_class text NOT NULL CHECK (safe_error_class IN (
        'unavailable', 'timeout', 'exhausted', 'capacity', 'stale', 'failed', 'overdue'
    )),
    first_observed_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    resolved_at timestamptz,
    CHECK ((status = 'firing' AND resolved_at IS NULL)
        OR (status = 'resolved' AND resolved_at IS NOT NULL)),
    UNIQUE (id, organisation_id)
);
CREATE UNIQUE INDEX operational_alerts_firing_dedupe_idx
    ON operational_alerts (organisation_id, alert_kind,
        coalesce(affected_resource_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE status = 'firing';
CREATE INDEX operational_alerts_recent_idx
    ON operational_alerts (organisation_id, last_observed_at DESC);

CREATE TABLE break_glass_requests (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    evidence_id uuid NOT NULL,
    operator_id uuid NOT NULL,
    reason_code text NOT NULL CHECK (reason_code IN (
        'incident_diagnosis', 'data_recovery', 'security_investigation'
    )),
    reason_key_id text NOT NULL CHECK (length(reason_key_id) BETWEEN 1 AND 100),
    reason_nonce bytea NOT NULL CHECK (octet_length(reason_nonce) = 12),
    reason_ciphertext bytea NOT NULL CHECK (octet_length(reason_ciphertext) BETWEEN 17 AND 4096),
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'approved', 'rejected', 'revoked')),
    requested_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    decided_at timestamptz,
    approved_by_membership_id uuid,
    revoked_at timestamptz,
    CHECK (expires_at > requested_at AND expires_at <= requested_at + interval '4 hours'),
    CHECK ((status = 'pending' AND decided_at IS NULL AND approved_by_membership_id IS NULL)
        OR (status = 'approved' AND decided_at IS NOT NULL AND approved_by_membership_id IS NOT NULL
            AND revoked_at IS NULL)
        OR (status = 'rejected' AND decided_at IS NOT NULL AND approved_by_membership_id IS NOT NULL
            AND revoked_at IS NULL)
        OR (status = 'revoked' AND decided_at IS NOT NULL AND approved_by_membership_id IS NOT NULL
            AND revoked_at IS NOT NULL)),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (evidence_id, organisation_id)
        REFERENCES evidence(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (approved_by_membership_id, organisation_id)
        REFERENCES memberships(id, organisation_id)
);
CREATE INDEX break_glass_pending_idx
    ON break_glass_requests (organisation_id, requested_at) WHERE status = 'pending';
CREATE INDEX break_glass_active_idx
    ON break_glass_requests (organisation_id, operator_id, evidence_id, expires_at)
    WHERE status = 'approved';

ALTER TABLE retention_extensions ADD COLUMN operator_id uuid;
ALTER TABLE retention_extensions ALTER COLUMN actor_membership_id DROP NOT NULL;
ALTER TABLE retention_extensions ADD CONSTRAINT retention_extensions_actor_check
    CHECK ((actor_membership_id IS NOT NULL) <> (operator_id IS NOT NULL));

ALTER TABLE operational_alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE operational_alerts FORCE ROW LEVEL SECURITY;
ALTER TABLE break_glass_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE break_glass_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY operational_alert_isolation ON operational_alerts
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY break_glass_request_isolation ON break_glass_requests
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'findworks_support') THEN
        CREATE ROLE findworks_support NOLOGIN NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO findworks_support;
GRANT SELECT ON discoveries, interview_sessions, interview_access_grants, runtime_credentials,
    invitations, shaping_runtime_work, interview_runtime_runs, invitation_delivery_jobs,
    retention_warnings, deletion_ledger, evidence, audit_records, operational_alerts,
    break_glass_requests, retention_extensions, findings_package_versions, interview_runtime_attempts,
    interview_completion_proposals TO findworks_support;
GRANT INSERT ON audit_records, break_glass_requests, retention_extensions,
    interview_runtime_runs TO findworks_support;
GRANT UPDATE (status, available_at, execution_correlation_id, updated_at)
    ON shaping_runtime_work TO findworks_support;
GRANT UPDATE (status, available_at, process_restarts, error_code,
    execution_correlation_id, updated_at) ON interview_runtime_runs TO findworks_support;
GRANT UPDATE (delivery_cycle, attempt_count, status, next_attempt_at, lease_owner,
    lease_expires_at, heartbeat_at, execution_correlation_id, updated_at)
    ON invitation_delivery_jobs TO findworks_support;
GRANT UPDATE (retention_due_at) ON discoveries TO findworks_support;
GRANT UPDATE (revoked_at) ON interview_access_grants TO findworks_support;
GRANT UPDATE (status, terminated_at, active_started_at, active_question_id,
    current_completion_proposal_id, revision, active_seconds)
    ON interview_sessions TO findworks_support;
GRANT UPDATE (revoked_at, delivery_status, provider_message_id) ON invitations TO findworks_support;
GRANT UPDATE (revoked_at) ON runtime_credentials TO findworks_support;
GRANT UPDATE (status, cancelled_at, lease_until, lease_owner, heartbeat_at, updated_at)
    ON interview_runtime_runs TO findworks_support;
GRANT UPDATE (outcome, finished_at) ON interview_runtime_attempts TO findworks_support;
GRANT UPDATE (status, decided_at) ON interview_completion_proposals TO findworks_support;
GRANT UPDATE (status, decided_at, approved_by_membership_id, revoked_at)
    ON break_glass_requests TO findworks_support;

GRANT SELECT, INSERT, UPDATE ON operational_alerts TO findworks_worker;
GRANT SELECT, UPDATE ON break_glass_requests TO findworks_application;
GRANT SELECT ON operational_alerts TO findworks_application;
GRANT UPDATE (origin_correlation_id, execution_correlation_id) ON shaping_runtime_work,
    interview_runtime_runs, invitation_delivery_jobs, retention_warnings, deletion_ledger
    TO findworks_application;
