CREATE TABLE organisations (
    id uuid PRIMARY KEY,
    name text NOT NULL CHECK (length(trim(name)) BETWEEN 1 AND 200),
    retention_days integer NOT NULL DEFAULT 90 CHECK (retention_days BETWEEN 1 AND 3650),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id uuid PRIMARY KEY,
    email text NOT NULL,
    email_verified_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX users_email_lower_unique ON users (lower(email));

CREATE TABLE memberships (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role text NOT NULL CHECK (role IN ('investigator')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (organisation_id, user_id),
    UNIQUE (id, organisation_id)
);

CREATE TABLE discoveries (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL REFERENCES organisations(id) ON DELETE CASCADE,
    owner_membership_id uuid NOT NULL,
    title text NOT NULL CHECK (length(trim(title)) BETWEEN 1 AND 200),
    objective text NOT NULL CHECK (length(trim(objective)) BETWEEN 1 AND 5000),
    status text NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'deletion_pending')),
    last_activity_at timestamptz NOT NULL DEFAULT now(),
    retention_due_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (owner_membership_id, organisation_id)
        REFERENCES memberships(id, organisation_id)
);

CREATE INDEX discoveries_organisation_id_idx ON discoveries (organisation_id);
CREATE INDEX discoveries_retention_due_at_idx ON discoveries (retention_due_at)
    WHERE retention_due_at IS NOT NULL;

ALTER TABLE organisations ENABLE ROW LEVEL SECURITY;
ALTER TABLE organisations FORCE ROW LEVEL SECURITY;
ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships FORCE ROW LEVEL SECURITY;
ALTER TABLE discoveries ENABLE ROW LEVEL SECURITY;
ALTER TABLE discoveries FORCE ROW LEVEL SECURITY;

CREATE POLICY organisation_isolation ON organisations
    USING (id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

CREATE POLICY membership_isolation ON memberships
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

CREATE POLICY discovery_isolation ON discoveries
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
