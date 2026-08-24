ALTER TABLE discoveries
    ADD COLUMN access_blocked_at timestamptz,
    ADD COLUMN purge_due_at timestamptz;
UPDATE discoveries
SET access_blocked_at = updated_at, purge_due_at = updated_at + interval '7 days'
WHERE status = 'deletion_pending';
ALTER TABLE discoveries
    ADD CONSTRAINT discoveries_deletion_shape_check CHECK (
        (status = 'active' AND access_blocked_at IS NULL AND purge_due_at IS NULL)
        OR (status = 'deletion_pending' AND access_blocked_at IS NOT NULL AND purge_due_at IS NOT NULL)
    );

ALTER TABLE interview_sessions
    ADD COLUMN access_blocked_at timestamptz,
    ADD COLUMN purge_due_at timestamptz,
    ADD CONSTRAINT interview_sessions_deletion_shape_check CHECK (
        (access_blocked_at IS NULL AND purge_due_at IS NULL)
        OR (access_blocked_at IS NOT NULL AND purge_due_at IS NOT NULL)
    );

ALTER TABLE findings_package_versions ADD COLUMN invalidated_at timestamptz;

ALTER TABLE audit_records ADD COLUMN expires_at timestamptz;
UPDATE audit_records SET expires_at = created_at + interval '12 months';
ALTER TABLE audit_records
    ALTER COLUMN expires_at SET DEFAULT (now() + interval '12 months'),
    ALTER COLUMN expires_at SET NOT NULL;
CREATE INDEX audit_records_expiry_idx ON audit_records(expires_at);

CREATE TABLE retention_extensions (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    previous_due_at timestamptz NOT NULL,
    extended_until timestamptz NOT NULL,
    actor_membership_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (extended_until > previous_due_at),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (discovery_id, organisation_id)
        REFERENCES discoveries(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (actor_membership_id, organisation_id)
        REFERENCES memberships(id, organisation_id)
);

CREATE TABLE retention_warnings (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    due_at_snapshot timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending', 'leased', 'sent', 'failed')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 3),
    available_at timestamptz NOT NULL,
    lease_owner uuid,
    lease_expires_at timestamptz,
    sent_at timestamptz,
    error_class text CHECK (error_class IS NULL OR error_class IN (
        'provider_unconfigured', 'provider_rejected', 'provider_unavailable'
    )),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'leased' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'leased' AND lease_owner IS NULL AND lease_expires_at IS NULL)),
    CHECK ((status = 'sent') = (sent_at IS NOT NULL)),
    UNIQUE (discovery_id, due_at_snapshot),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (discovery_id, organisation_id)
        REFERENCES discoveries(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE deletion_ledger (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL REFERENCES organisations(id),
    target_kind text NOT NULL CHECK (target_kind IN ('discovery', 'interview_session')),
    target_id uuid NOT NULL,
    requested_by_kind text NOT NULL CHECK (requested_by_kind IN ('investigator', 'support', 'system')),
    requested_by_id uuid,
    stage text NOT NULL DEFAULT 'blocked' CHECK (stage IN ('blocked', 'purging', 'completed')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 20),
    requested_at timestamptz NOT NULL,
    access_blocked_at timestamptz NOT NULL,
    purge_deadline timestamptz NOT NULL,
    available_at timestamptz NOT NULL,
    lease_owner uuid,
    lease_expires_at timestamptz,
    completed_at timestamptz,
    backup_expiry_due_at timestamptz,
    safe_error_class text CHECK (safe_error_class IS NULL OR safe_error_class IN ('database_error')),
    CHECK ((stage = 'purging' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (stage <> 'purging' AND lease_owner IS NULL AND lease_expires_at IS NULL)),
    CHECK ((stage = 'completed') = (completed_at IS NOT NULL)),
    CHECK ((completed_at IS NULL AND backup_expiry_due_at IS NULL)
        OR backup_expiry_due_at = completed_at + interval '30 days'),
    UNIQUE (target_kind, target_id),
    UNIQUE (id, organisation_id)
);

CREATE INDEX retention_extensions_discovery_idx
    ON retention_extensions(discovery_id, created_at DESC);
CREATE INDEX retention_warnings_ready_idx
    ON retention_warnings(available_at, created_at) WHERE status IN ('pending', 'leased');
CREATE INDEX deletion_ledger_ready_idx
    ON deletion_ledger(available_at, requested_at) WHERE stage IN ('blocked', 'purging');
CREATE INDEX deletion_ledger_backup_expiry_idx
    ON deletion_ledger(backup_expiry_due_at) WHERE backup_expiry_due_at IS NOT NULL;

ALTER TABLE retention_extensions ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_extensions FORCE ROW LEVEL SECURITY;
ALTER TABLE retention_warnings ENABLE ROW LEVEL SECURITY;
ALTER TABLE retention_warnings FORCE ROW LEVEL SECURITY;
ALTER TABLE deletion_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE deletion_ledger FORCE ROW LEVEL SECURITY;

CREATE POLICY retention_extension_isolation ON retention_extensions
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY retention_warning_isolation ON retention_warnings
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY deletion_ledger_isolation ON deletion_ledger
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

GRANT SELECT, INSERT ON retention_extensions, retention_warnings, deletion_ledger TO findworks_application;
GRANT UPDATE (retention_due_at, status, access_blocked_at, purge_due_at) ON discoveries TO findworks_application;
GRANT UPDATE (access_blocked_at, purge_due_at) ON interview_sessions TO findworks_application;
GRANT UPDATE (invalidated_at) ON findings_package_versions TO findworks_application;
GRANT UPDATE (status, attempt_count, available_at, lease_owner, lease_expires_at,
    sent_at, error_class, updated_at) ON retention_warnings TO findworks_application;
GRANT UPDATE (stage, attempts, available_at, lease_owner, lease_expires_at,
    completed_at, backup_expiry_due_at, safe_error_class) ON deletion_ledger TO findworks_application;
GRANT DELETE ON audit_records TO findworks_application;
