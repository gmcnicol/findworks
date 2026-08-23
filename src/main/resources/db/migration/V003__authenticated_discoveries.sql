CREATE INDEX discoveries_owner_membership_id_idx ON discoveries(owner_membership_id);

CREATE TABLE audit_records (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
    actor_kind text NOT NULL CHECK (actor_kind IN ('investigator', 'system')),
    actor_id uuid,
    action text NOT NULL CHECK (length(action) BETWEEN 1 AND 100),
    resource_kind text NOT NULL CHECK (length(resource_kind) BETWEEN 1 AND 100),
    resource_id uuid,
    outcome text NOT NULL CHECK (outcome IN ('success', 'denied', 'failed')),
    correlation_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_records_organisation_created_at_idx ON audit_records(organisation_id, created_at);

ALTER TABLE audit_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_records FORCE ROW LEVEL SECURITY;

CREATE POLICY audit_isolation ON audit_records
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
