CREATE TABLE discovery_participants (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    intended_name text NOT NULL CHECK (length(trim(intended_name)) BETWEEN 1 AND 1000),
    email text NOT NULL CHECK (length(trim(email)) BETWEEN 3 AND 320),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (discovery_id, organisation_id)
        REFERENCES discoveries(id, organisation_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX discovery_participants_email_unique
    ON discovery_participants(discovery_id, lower(email));

ALTER TABLE invitations
    ADD COLUMN organisation_id uuid,
    ADD COLUMN participant_id uuid,
    ADD COLUMN recipient_email text,
    ADD COLUMN token_key_id text,
    ADD COLUMN send_confirmed_at timestamptz,
    ADD COLUMN delivery_status text NOT NULL DEFAULT 'legacy_inert',
    ADD COLUMN provider_message_id text;

UPDATE invitations i
SET organisation_id = m.organisation_id,
    recipient_email = m.interviewee_email,
    token_key_id = 'legacy-unavailable'
FROM interview_missions m
WHERE m.id = i.interview_mission_id;

INSERT INTO discovery_participants (
    id, organisation_id, discovery_id, intended_name, email
)
SELECT gen_random_uuid(), m.organisation_id, m.discovery_id, m.interviewee_name, m.interviewee_email
FROM invitations i
JOIN interview_missions m ON m.id = i.interview_mission_id
WHERE m.interviewee_email IS NOT NULL
ON CONFLICT (discovery_id, lower(email)) DO NOTHING;

UPDATE invitations i
SET participant_id = p.id
FROM interview_missions m
JOIN discovery_participants p
  ON p.discovery_id = m.discovery_id AND lower(p.email) = lower(m.interviewee_email)
WHERE m.id = i.interview_mission_id;

ALTER TABLE invitations
    ALTER COLUMN organisation_id SET NOT NULL,
    ALTER COLUMN participant_id SET NOT NULL,
    ALTER COLUMN recipient_email SET NOT NULL,
    ALTER COLUMN token_key_id SET NOT NULL,
    ADD CONSTRAINT invitations_recipient_check CHECK (length(trim(recipient_email)) BETWEEN 3 AND 320),
    ADD CONSTRAINT invitations_key_id_check CHECK (length(trim(token_key_id)) BETWEEN 1 AND 100),
    ADD CONSTRAINT invitations_delivery_status_check CHECK (delivery_status IN (
        'legacy_inert', 'pending', 'provider_accepted', 'failed', 'redeemed', 'revoked'
    )),
    ADD CONSTRAINT invitations_send_expiry_check CHECK (
        (send_confirmed_at IS NULL AND delivery_status = 'legacy_inert')
        OR (send_confirmed_at IS NOT NULL AND expires_at = send_confirmed_at + interval '7 days')
    ),
    ADD CONSTRAINT invitations_id_org_unique UNIQUE (id, organisation_id),
    ADD CONSTRAINT invitations_mission_org_fk FOREIGN KEY (interview_mission_id, organisation_id)
        REFERENCES interview_missions(id, organisation_id) ON DELETE CASCADE,
    ADD CONSTRAINT invitations_participant_org_fk FOREIGN KEY (participant_id, organisation_id)
        REFERENCES discovery_participants(id, organisation_id);

CREATE UNIQUE INDEX invitations_one_current_recipient_version
    ON invitations(interview_mission_id, participant_id)
    WHERE revoked_at IS NULL;

CREATE TABLE invitation_delivery_jobs (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    invitation_id uuid NOT NULL,
    delivery_cycle integer NOT NULL DEFAULT 1 CHECK (delivery_cycle > 0),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 3),
    status text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'leased', 'accepted', 'failed')),
    next_attempt_at timestamptz NOT NULL,
    lease_owner uuid,
    lease_expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'leased' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status <> 'leased' AND lease_owner IS NULL AND lease_expires_at IS NULL)),
    UNIQUE (invitation_id),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (invitation_id, organisation_id)
        REFERENCES invitations(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE invitation_delivery_attempts (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    invitation_id uuid NOT NULL,
    delivery_cycle integer NOT NULL CHECK (delivery_cycle > 0),
    attempt_number integer NOT NULL CHECK (attempt_number BETWEEN 1 AND 3),
    outcome text NOT NULL CHECK (outcome IN ('provider_accepted', 'failed')),
    error_class text CHECK (error_class IN ('provider_unconfigured', 'provider_rejected', 'provider_unavailable')),
    provider_message_id text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((outcome = 'provider_accepted' AND error_class IS NULL AND provider_message_id IS NOT NULL)
        OR (outcome = 'failed' AND error_class IS NOT NULL AND provider_message_id IS NULL)),
    UNIQUE (invitation_id, delivery_cycle, attempt_number),
    FOREIGN KEY (invitation_id, organisation_id)
        REFERENCES invitations(id, organisation_id) ON DELETE CASCADE
);

CREATE INDEX invitation_jobs_ready_idx
    ON invitation_delivery_jobs(next_attempt_at) WHERE status IN ('pending', 'leased');
CREATE INDEX invitation_attempts_invitation_idx
    ON invitation_delivery_attempts(invitation_id, delivery_cycle, attempt_number);

DROP POLICY invitation_isolation ON invitations;
CREATE POLICY invitation_isolation ON invitations
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

ALTER TABLE discovery_participants ENABLE ROW LEVEL SECURITY;
ALTER TABLE discovery_participants FORCE ROW LEVEL SECURITY;
ALTER TABLE invitation_delivery_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitation_delivery_jobs FORCE ROW LEVEL SECURITY;
ALTER TABLE invitation_delivery_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE invitation_delivery_attempts FORCE ROW LEVEL SECURITY;

CREATE POLICY participant_isolation ON discovery_participants
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY invitation_job_isolation ON invitation_delivery_jobs
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY invitation_attempt_isolation ON invitation_delivery_attempts
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

REVOKE UPDATE, DELETE ON invitations FROM findworks_application;
GRANT SELECT, INSERT ON invitations TO findworks_application;
GRANT UPDATE (delivery_status, provider_message_id, redeemed_at, revoked_at) ON invitations TO findworks_application;
GRANT SELECT, INSERT ON discovery_participants TO findworks_application;
GRANT SELECT, INSERT ON invitation_delivery_jobs TO findworks_application;
GRANT UPDATE (
    delivery_cycle, attempt_count, status, next_attempt_at, lease_owner, lease_expires_at, updated_at
) ON invitation_delivery_jobs TO findworks_application;
GRANT SELECT, INSERT ON invitation_delivery_attempts TO findworks_application;
