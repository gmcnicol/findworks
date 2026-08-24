ALTER TABLE evidence
    ADD COLUMN organisation_id uuid,
    ADD COLUMN discovery_id uuid,
    ADD COLUMN interview_mission_id uuid,
    ADD COLUMN participant_id uuid,
    ADD COLUMN question_id uuid,
    ADD COLUMN source_type text NOT NULL DEFAULT 'legacy';

UPDATE evidence e
SET organisation_id = s.organisation_id,
    discovery_id = s.discovery_id,
    interview_mission_id = s.interview_mission_id,
    participant_id = s.participant_id
FROM interview_sessions s
WHERE s.id = e.interview_session_id;

ALTER TABLE evidence
    ALTER COLUMN organisation_id SET NOT NULL,
    ALTER COLUMN discovery_id SET NOT NULL,
    ALTER COLUMN interview_mission_id SET NOT NULL,
    ALTER COLUMN participant_id SET NOT NULL,
    DROP CONSTRAINT evidence_investigation_item_id_fkey,
    ADD CONSTRAINT evidence_source_type_check
        CHECK ((source_type = 'legacy' AND question_id IS NULL)
            OR (source_type = 'interviewee_answer' AND question_id IS NOT NULL)),
    ADD CONSTRAINT evidence_id_org_unique UNIQUE (id, organisation_id),
    ADD CONSTRAINT evidence_id_session_org_unique UNIQUE (id, interview_session_id, organisation_id),
    ADD CONSTRAINT evidence_session_scope_fk
        FOREIGN KEY (interview_session_id, interview_mission_id, discovery_id, organisation_id)
        REFERENCES interview_sessions(id, interview_mission_id, discovery_id, organisation_id) ON DELETE CASCADE,
    ADD CONSTRAINT evidence_participant_scope_fk
        FOREIGN KEY (participant_id, discovery_id, organisation_id)
        REFERENCES discovery_participants(id, discovery_id, organisation_id) ON DELETE CASCADE,
    ADD CONSTRAINT evidence_question_scope_fk
        FOREIGN KEY (question_id, interview_session_id, organisation_id)
        REFERENCES interview_questions(id, interview_session_id, organisation_id) ON DELETE CASCADE,
    ADD CONSTRAINT evidence_item_scope_fk
        FOREIGN KEY (investigation_item_id, interview_mission_id, organisation_id)
        REFERENCES investigation_items(id, interview_mission_id, organisation_id) ON DELETE CASCADE;

ALTER TABLE evidence ALTER COLUMN source_type DROP DEFAULT;

ALTER TABLE evidence DROP CONSTRAINT evidence_interview_session_id_investigation_item_id_key;
CREATE UNIQUE INDEX evidence_one_answer_per_question_idx
    ON evidence(question_id) WHERE source_type = 'interviewee_answer';
CREATE INDEX evidence_session_created_idx ON evidence(interview_session_id, created_at);

DROP POLICY evidence_isolation ON evidence;
CREATE POLICY evidence_isolation ON evidence
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

REVOKE UPDATE, DELETE ON evidence FROM findworks_application;
GRANT SELECT, INSERT ON evidence TO findworks_application;

ALTER TABLE interview_questions ADD COLUMN answered_at timestamptz;
GRANT UPDATE (answered_at) ON interview_questions TO findworks_application;

CREATE TABLE investigation_results (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    status text NOT NULL CHECK (status = 'exploring'),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (interview_session_id, investigation_item_id),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (interview_session_id, interview_mission_id, discovery_id, organisation_id)
        REFERENCES interview_sessions(id, interview_mission_id, discovery_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (investigation_item_id, interview_mission_id, organisation_id)
        REFERENCES investigation_items(id, interview_mission_id, organisation_id) ON DELETE CASCADE
);

CREATE INDEX investigation_results_session_idx
    ON investigation_results(interview_session_id, status);
ALTER TABLE investigation_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE investigation_results FORCE ROW LEVEL SECURITY;
CREATE POLICY investigation_result_isolation ON investigation_results
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
GRANT SELECT, INSERT ON investigation_results TO findworks_application;

ALTER TABLE interview_runtime_runs
    ADD COLUMN evidence_id uuid,
    DROP CONSTRAINT interview_runtime_runs_trigger_check,
    ADD CONSTRAINT interview_runtime_runs_trigger_check
        CHECK (trigger IN ('session_start', 'accepted_evidence')),
    ADD CONSTRAINT interview_runtime_runs_evidence_check
        CHECK ((trigger = 'session_start' AND evidence_id IS NULL)
            OR (trigger = 'accepted_evidence' AND evidence_id IS NOT NULL)),
    ADD CONSTRAINT interview_runtime_runs_evidence_unique UNIQUE (evidence_id),
    ADD CONSTRAINT interview_runtime_runs_evidence_scope_fk
        FOREIGN KEY (evidence_id, interview_session_id, organisation_id)
        REFERENCES evidence(id, interview_session_id, organisation_id) ON DELETE CASCADE;
