ALTER TABLE interview_missions
    ADD CONSTRAINT interview_missions_id_discovery_org_unique
        UNIQUE (id, discovery_id, organisation_id);

ALTER TABLE discovery_participants
    ADD CONSTRAINT discovery_participants_id_discovery_org_unique
        UNIQUE (id, discovery_id, organisation_id);

ALTER TABLE invitations ADD COLUMN discovery_id uuid;

UPDATE invitations i
SET discovery_id = m.discovery_id
FROM interview_missions m
WHERE m.id = i.interview_mission_id;

ALTER TABLE invitations
    ALTER COLUMN discovery_id SET NOT NULL,
    ADD CONSTRAINT invitations_mission_discovery_org_fk
        FOREIGN KEY (interview_mission_id, discovery_id, organisation_id)
        REFERENCES interview_missions(id, discovery_id, organisation_id) ON DELETE CASCADE,
    ADD CONSTRAINT invitations_participant_discovery_org_fk
        FOREIGN KEY (participant_id, discovery_id, organisation_id)
        REFERENCES discovery_participants(id, discovery_id, organisation_id);

ALTER TABLE interview_sessions
    ADD COLUMN organisation_id uuid,
    ADD COLUMN discovery_id uuid,
    ADD COLUMN participant_id uuid,
    ADD COLUMN revision integer NOT NULL DEFAULT 0 CHECK (revision >= 0);

UPDATE interview_sessions s
SET organisation_id = m.organisation_id,
    discovery_id = m.discovery_id,
    participant_id = (
        SELECT i.participant_id
        FROM invitations i
        WHERE i.interview_mission_id = s.interview_mission_id
        ORDER BY i.redeemed_at DESC NULLS LAST, i.created_at DESC
        LIMIT 1
    )
FROM interview_missions m
WHERE m.id = s.interview_mission_id;

ALTER TABLE interview_sessions
    ALTER COLUMN organisation_id SET NOT NULL,
    ALTER COLUMN discovery_id SET NOT NULL,
    ALTER COLUMN participant_id SET NOT NULL,
    DROP CONSTRAINT interview_sessions_status_check,
    ADD CONSTRAINT interview_sessions_status_check
        CHECK (status IN ('not_started', 'active', 'in_progress', 'completed')),
    ADD CONSTRAINT interview_sessions_start_check
        CHECK ((status = 'not_started' AND started_at IS NULL)
            OR (status <> 'not_started' AND started_at IS NOT NULL)),
    ADD CONSTRAINT interview_sessions_id_participant_org_unique
        UNIQUE (id, participant_id, organisation_id),
    ADD CONSTRAINT interview_sessions_mission_discovery_org_fk
        FOREIGN KEY (interview_mission_id, discovery_id, organisation_id)
        REFERENCES interview_missions(id, discovery_id, organisation_id) ON DELETE CASCADE,
    ADD CONSTRAINT interview_sessions_participant_discovery_org_fk
        FOREIGN KEY (participant_id, discovery_id, organisation_id)
        REFERENCES discovery_participants(id, discovery_id, organisation_id);

ALTER TABLE interview_access_grants
    ADD COLUMN organisation_id uuid,
    ADD COLUMN participant_id uuid;

UPDATE interview_access_grants g
SET organisation_id = s.organisation_id,
    participant_id = s.participant_id
FROM interview_sessions s
WHERE s.id = g.interview_session_id;

ALTER TABLE interview_access_grants
    ALTER COLUMN organisation_id SET NOT NULL,
    ALTER COLUMN participant_id SET NOT NULL,
    ADD CONSTRAINT interview_access_grants_session_participant_org_fk
        FOREIGN KEY (interview_session_id, participant_id, organisation_id)
        REFERENCES interview_sessions(id, participant_id, organisation_id) ON DELETE CASCADE;

CREATE INDEX interview_sessions_participant_idx
    ON interview_sessions(participant_id, interview_mission_id);
CREATE INDEX interview_access_grants_session_active_idx
    ON interview_access_grants(interview_session_id, expires_at) WHERE revoked_at IS NULL;

DROP POLICY session_isolation ON interview_sessions;
CREATE POLICY session_isolation ON interview_sessions
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

DROP POLICY grant_isolation ON interview_access_grants;
CREATE POLICY grant_isolation ON interview_access_grants
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

REVOKE UPDATE, DELETE ON interview_access_grants FROM findworks_application;
GRANT SELECT, INSERT ON interview_access_grants TO findworks_application;
GRANT UPDATE (revoked_at) ON interview_access_grants TO findworks_application;
