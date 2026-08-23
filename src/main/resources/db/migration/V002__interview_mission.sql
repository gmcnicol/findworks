INSERT INTO organisations (id, name)
VALUES ('10000000-0000-0000-0000-000000000001', 'FindWorks Pilot');

INSERT INTO users (id, email, email_verified_at)
VALUES ('20000000-0000-0000-0000-000000000001', 'investigator@findworks.local', now());

INSERT INTO memberships (id, organisation_id, user_id, role)
VALUES (
    '30000000-0000-0000-0000-000000000001',
    '10000000-0000-0000-0000-000000000001',
    '20000000-0000-0000-0000-000000000001',
    'investigator'
);

CREATE TABLE interview_missions (
    id uuid PRIMARY KEY,
    discovery_id uuid NOT NULL REFERENCES discoveries(id) ON DELETE CASCADE,
    version integer NOT NULL DEFAULT 1,
    status text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft', 'approved')),
    interviewee_name text NOT NULL CHECK (length(trim(interviewee_name)) BETWEEN 1 AND 200),
    interviewee_email text NOT NULL CHECK (length(trim(interviewee_email)) BETWEEN 3 AND 320),
    expected_minutes integer NOT NULL DEFAULT 20 CHECK (expected_minutes BETWEEN 5 AND 120),
    approved_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, discovery_id)
);

CREATE TABLE investigation_items (
    id uuid PRIMARY KEY,
    interview_mission_id uuid NOT NULL REFERENCES interview_missions(id) ON DELETE CASCADE,
    position integer NOT NULL CHECK (position >= 0),
    knowledge_gap text NOT NULL CHECK (length(trim(knowledge_gap)) BETWEEN 1 AND 1000),
    opening_question text NOT NULL CHECK (length(trim(opening_question)) BETWEEN 1 AND 2000),
    required boolean NOT NULL DEFAULT true,
    UNIQUE (interview_mission_id, position),
    UNIQUE (id, interview_mission_id)
);

CREATE TABLE invitations (
    id uuid PRIMARY KEY,
    interview_mission_id uuid NOT NULL REFERENCES interview_missions(id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    redeemed_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE interview_sessions (
    id uuid PRIMARY KEY,
    interview_mission_id uuid NOT NULL UNIQUE REFERENCES interview_missions(id) ON DELETE CASCADE,
    status text NOT NULL DEFAULT 'not_started' CHECK (status IN ('not_started', 'in_progress', 'completed')),
    current_position integer NOT NULL DEFAULT 0 CHECK (current_position >= 0),
    started_at timestamptz,
    completed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE interview_access_grants (
    id uuid PRIMARY KEY,
    interview_session_id uuid NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE evidence (
    id uuid PRIMARY KEY,
    interview_session_id uuid NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
    investigation_item_id uuid NOT NULL REFERENCES investigation_items(id) ON DELETE RESTRICT,
    answer text NOT NULL CHECK (length(trim(answer)) BETWEEN 1 AND 10000),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (interview_session_id, investigation_item_id)
);

CREATE INDEX interview_missions_discovery_id_idx ON interview_missions(discovery_id);
CREATE INDEX investigation_items_mission_id_idx ON investigation_items(interview_mission_id);
CREATE INDEX invitations_mission_id_idx ON invitations(interview_mission_id);
CREATE INDEX evidence_session_id_idx ON evidence(interview_session_id);

ALTER TABLE interview_missions ENABLE ROW LEVEL SECURITY;
ALTER TABLE interview_missions FORCE ROW LEVEL SECURITY;
ALTER TABLE investigation_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE investigation_items FORCE ROW LEVEL SECURITY;
ALTER TABLE invitations ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitations FORCE ROW LEVEL SECURITY;
ALTER TABLE interview_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE interview_sessions FORCE ROW LEVEL SECURITY;
ALTER TABLE interview_access_grants ENABLE ROW LEVEL SECURITY;
ALTER TABLE interview_access_grants FORCE ROW LEVEL SECURITY;
ALTER TABLE evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence FORCE ROW LEVEL SECURITY;

CREATE POLICY mission_isolation ON interview_missions USING (
    EXISTS (SELECT 1 FROM discoveries d WHERE d.id = discovery_id)
);
CREATE POLICY item_isolation ON investigation_items USING (
    EXISTS (SELECT 1 FROM interview_missions m WHERE m.id = interview_mission_id)
);
CREATE POLICY invitation_isolation ON invitations USING (
    EXISTS (SELECT 1 FROM interview_missions m WHERE m.id = interview_mission_id)
);
CREATE POLICY session_isolation ON interview_sessions USING (
    EXISTS (SELECT 1 FROM interview_missions m WHERE m.id = interview_mission_id)
);
CREATE POLICY grant_isolation ON interview_access_grants USING (
    EXISTS (SELECT 1 FROM interview_sessions s WHERE s.id = interview_session_id)
);
CREATE POLICY evidence_isolation ON evidence USING (
    EXISTS (SELECT 1 FROM interview_sessions s WHERE s.id = interview_session_id)
);
