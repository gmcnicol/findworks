ALTER TABLE discoveries
    ADD CONSTRAINT discoveries_id_organisation_unique UNIQUE (id, organisation_id);

CREATE TABLE discovery_shaping_sessions (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (discovery_id),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (discovery_id, organisation_id)
        REFERENCES discoveries(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE discovery_shaping_messages (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    shaping_session_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    author_kind text NOT NULL CHECK (author_kind IN ('investigator', 'agent')),
    author_id uuid,
    content text NOT NULL CHECK (length(trim(content)) BETWEEN 1 AND 10000),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (shaping_session_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (shaping_session_id, organisation_id)
        REFERENCES discovery_shaping_sessions(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (author_id, organisation_id)
        REFERENCES memberships(id, organisation_id),
    CHECK ((author_kind = 'investigator' AND author_id IS NOT NULL)
        OR (author_kind = 'agent' AND author_id IS NULL))
);

CREATE TABLE shaping_runtime_work (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    shaping_session_id uuid NOT NULL,
    trigger_message_id uuid NOT NULL,
    response_message_id uuid,
    status text NOT NULL DEFAULT 'queued' CHECK (status IN ('queued', 'running', 'succeeded', 'failed')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    error_code text CHECK (error_code IS NULL OR length(error_code) BETWEEN 1 AND 100),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (trigger_message_id),
    FOREIGN KEY (shaping_session_id, organisation_id)
        REFERENCES discovery_shaping_sessions(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (trigger_message_id, organisation_id)
        REFERENCES discovery_shaping_messages(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (response_message_id, organisation_id)
        REFERENCES discovery_shaping_messages(id, organisation_id) ON DELETE CASCADE
);

CREATE INDEX shaping_messages_session_created_idx
    ON discovery_shaping_messages(shaping_session_id, position);
CREATE INDEX shaping_runtime_work_ready_idx
    ON shaping_runtime_work(available_at, created_at)
    WHERE status IN ('queued', 'running');

ALTER TABLE discovery_shaping_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE discovery_shaping_sessions FORCE ROW LEVEL SECURITY;
ALTER TABLE discovery_shaping_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE discovery_shaping_messages FORCE ROW LEVEL SECURITY;
ALTER TABLE shaping_runtime_work ENABLE ROW LEVEL SECURITY;
ALTER TABLE shaping_runtime_work FORCE ROW LEVEL SECURITY;

CREATE POLICY shaping_session_isolation ON discovery_shaping_sessions
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY shaping_message_isolation ON discovery_shaping_messages
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY shaping_work_isolation ON shaping_runtime_work
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON discovery_shaping_sessions TO findworks_application;
GRANT SELECT, INSERT ON discovery_shaping_messages TO findworks_application;
GRANT SELECT, INSERT, UPDATE ON shaping_runtime_work TO findworks_application;
