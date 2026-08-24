CREATE TABLE recovery_drill_results (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL REFERENCES organisations(id),
    operator_id uuid NOT NULL,
    source_kind text NOT NULL CHECK (source_kind IN ('synthetic', 'provider')),
    backup_id text NOT NULL CHECK (length(backup_id) BETWEEN 1 AND 500),
    candidate_restore_id text NOT NULL CHECK (length(candidate_restore_id) BETWEEN 1 AND 500),
    ledger_restore_id text NOT NULL CHECK (length(ledger_restore_id) BETWEEN 1 AND 500),
    requested_restore_at timestamptz NOT NULL,
    achieved_restore_at timestamptz NOT NULL,
    source_timeline text NOT NULL CHECK (length(source_timeline) BETWEEN 1 AND 200),
    ledger_high_water_at timestamptz,
    application_image_digest text NOT NULL CHECK (
        application_image_digest ~ '^.+@sha256:[a-f0-9]{64}$'),
    schema_version integer NOT NULL CHECK (schema_version > 0),
    encrypted_at_rest boolean,
    encrypted_in_transit boolean,
    recovery_point_gap_seconds integer CHECK (recovery_point_gap_seconds BETWEEN 0 AND 86400),
    snapshot_interval_hours integer CHECK (snapshot_interval_hours BETWEEN 1 AND 744),
    backup_retention_days integer CHECK (backup_retention_days BETWEEN 1 AND 3650),
    ledger_entry_count integer NOT NULL CHECK (ledger_entry_count >= 0),
    replayed_entry_count integer NOT NULL CHECK (
        replayed_entry_count >= 0 AND replayed_entry_count <= ledger_entry_count),
    evidence_check_count integer NOT NULL CHECK (evidence_check_count >= 0),
    provenance_check_count integer NOT NULL CHECK (provenance_check_count >= 0),
    graph_verified boolean NOT NULL,
    access_denial_verified boolean NOT NULL,
    retention_verified boolean NOT NULL,
    deletion_verified boolean NOT NULL,
    duration_seconds integer NOT NULL CHECK (duration_seconds >= 0),
    outcome text NOT NULL CHECK (outcome IN ('verified_synthetic', 'ready', 'failed')),
    safe_failure_class text CHECK (safe_failure_class IS NULL OR safe_failure_class IN (
        'provider_policy', 'bundle_integrity', 'restore_identity', 'ledger_replay',
        'graph_invalid', 'access_invalid', 'retention_invalid', 'deletion_invalid',
        'recovery_timeout', 'database_unavailable'
    )),
    completed_at timestamptz NOT NULL,
    CHECK ((outcome = 'failed') = (safe_failure_class IS NOT NULL)),
    CHECK (outcome = 'failed' OR (
        graph_verified AND access_denial_verified AND retention_verified AND deletion_verified
        AND replayed_entry_count = ledger_entry_count AND duration_seconds <= 14400
    )),
    CHECK (outcome <> 'ready' OR (
        source_kind = 'provider' AND encrypted_at_rest AND encrypted_in_transit
        AND recovery_point_gap_seconds <= 300
        AND snapshot_interval_hours <= 24 AND backup_retention_days <= 30
        AND abs(extract(epoch FROM achieved_restore_at - requested_restore_at)) <= 300
    )),
    UNIQUE (id, organisation_id)
);

CREATE INDEX recovery_drill_results_latest_idx
    ON recovery_drill_results (organisation_id, completed_at DESC);
CREATE INDEX recovery_drill_results_backup_idx
    ON recovery_drill_results (organisation_id, backup_id, completed_at DESC);

ALTER TABLE recovery_drill_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE recovery_drill_results FORCE ROW LEVEL SECURITY;
CREATE POLICY recovery_drill_result_isolation ON recovery_drill_results
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'findworks_recovery') THEN
        CREATE ROLE findworks_recovery NOLOGIN NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO findworks_recovery;
GRANT SELECT ON organisations, discoveries, interview_missions, investigation_items,
    discovery_participants, invitations, interview_sessions, interview_access_grants,
    evidence, knowledge_items, knowledge_item_versions, knowledge_item_evidence_citations,
    findings_package_versions, retention_warnings, deletion_ledger, runtime_credentials,
    interview_runtime_runs, runtime_checkpoints, shaping_runtime_work, audit_records,
    recovery_drill_results, flyway_schema_history TO findworks_recovery;
GRANT INSERT ON deletion_ledger, audit_records, recovery_drill_results TO findworks_recovery;
GRANT UPDATE (requested_by_kind, requested_by_id, stage, attempts, requested_at,
    access_blocked_at, purge_deadline, available_at, lease_owner, lease_expires_at,
    heartbeat_at, completed_at, backup_expiry_due_at, safe_error_class,
    origin_correlation_id, execution_correlation_id)
    ON deletion_ledger TO findworks_recovery;
GRANT UPDATE (status, access_blocked_at, purge_due_at) ON discoveries TO findworks_recovery;
GRANT UPDATE (access_blocked_at, purge_due_at) ON interview_sessions TO findworks_recovery;
GRANT UPDATE (revoked_at, delivery_status) ON invitations TO findworks_recovery;
GRANT UPDATE (revoked_at) ON interview_access_grants, runtime_credentials TO findworks_recovery;
GRANT UPDATE (status, cancelled_at, lease_until, lease_owner, heartbeat_at, updated_at)
    ON interview_runtime_runs TO findworks_recovery;
GRANT UPDATE (invalidated_at) ON findings_package_versions TO findworks_recovery;
GRANT DELETE ON discoveries, invitations, interview_sessions, discovery_participants
    TO findworks_recovery;

GRANT SELECT ON recovery_drill_results TO findworks_application, findworks_worker, findworks_support;
