ALTER TABLE interview_missions
    ADD COLUMN organisation_id uuid,
    ADD COLUMN lineage_id uuid,
    ADD COLUMN source_proposal_id uuid,
    ADD COLUMN objective text,
    ADD COLUMN desired_outcome text,
    ADD COLUMN interviewee_relevance text,
    ADD COLUMN completion_criteria text,
    ADD COLUMN expected_commitment text,
    ADD COLUMN data_use_summary text,
    ADD COLUMN superseded_at timestamptz;

UPDATE interview_missions m
SET organisation_id = d.organisation_id,
    lineage_id = m.id,
    objective = d.objective,
    desired_outcome = d.objective,
    interviewee_relevance = 'Legacy Mission interviewee',
    completion_criteria = 'Complete every required Investigation Item.',
    expected_commitment = m.expected_minutes || ' minutes',
    data_use_summary = 'Use answers for this Discovery.'
FROM discoveries d
WHERE d.id = m.discovery_id;

ALTER TABLE interview_missions
    ALTER COLUMN organisation_id SET NOT NULL,
    ALTER COLUMN lineage_id SET NOT NULL,
    ALTER COLUMN objective SET NOT NULL,
    ALTER COLUMN desired_outcome SET NOT NULL,
    ALTER COLUMN interviewee_relevance SET NOT NULL,
    ALTER COLUMN completion_criteria SET NOT NULL,
    ALTER COLUMN expected_commitment SET NOT NULL,
    ALTER COLUMN data_use_summary SET NOT NULL,
    ALTER COLUMN interviewee_email DROP NOT NULL,
    DROP CONSTRAINT interview_missions_status_check,
    DROP CONSTRAINT interview_missions_interviewee_name_check,
    ADD CONSTRAINT interview_missions_status_check CHECK (status IN ('draft', 'approved', 'superseded')),
    ADD CONSTRAINT interview_missions_interviewee_name_check CHECK (length(interviewee_name) <= 1000),
    ADD CONSTRAINT interview_missions_objective_check CHECK (length(objective) <= 4000),
    ADD CONSTRAINT interview_missions_desired_outcome_check CHECK (length(desired_outcome) <= 4000),
    ADD CONSTRAINT interview_missions_relevance_check CHECK (length(interviewee_relevance) <= 4000),
    ADD CONSTRAINT interview_missions_completion_check CHECK (length(completion_criteria) <= 4000),
    ADD CONSTRAINT interview_missions_commitment_check CHECK (length(expected_commitment) <= 1000),
    ADD CONSTRAINT interview_missions_data_use_check CHECK (length(data_use_summary) <= 4000),
    ADD CONSTRAINT interview_missions_lineage_version_unique UNIQUE (lineage_id, version),
    ADD CONSTRAINT interview_missions_id_org_unique UNIQUE (id, organisation_id),
    ADD CONSTRAINT interview_missions_organisation_fk FOREIGN KEY (organisation_id)
        REFERENCES organisations(id) ON DELETE CASCADE,
    ADD CONSTRAINT interview_missions_proposal_fk FOREIGN KEY (source_proposal_id, organisation_id)
        REFERENCES interview_mission_proposals(id, organisation_id);

DROP POLICY mission_isolation ON interview_missions;
CREATE POLICY mission_isolation ON interview_missions
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

ALTER TABLE investigation_items
    ADD COLUMN organisation_id uuid,
    ADD COLUMN importance text,
    ADD COLUMN priority text,
    ADD COLUMN relevant_context text;

UPDATE investigation_items i
SET organisation_id = m.organisation_id,
    importance = 'Legacy Investigation Item',
    priority = 'medium',
    relevant_context = 'No additional context recorded.'
FROM interview_missions m
WHERE m.id = i.interview_mission_id;

ALTER TABLE investigation_items
    ALTER COLUMN organisation_id SET NOT NULL,
    ALTER COLUMN importance SET NOT NULL,
    ALTER COLUMN priority SET NOT NULL,
    ALTER COLUMN relevant_context SET NOT NULL,
    ALTER COLUMN opening_question DROP NOT NULL,
    DROP CONSTRAINT investigation_items_knowledge_gap_check,
    ADD CONSTRAINT investigation_items_knowledge_gap_check CHECK (length(trim(knowledge_gap)) BETWEEN 1 AND 4000),
    ADD CONSTRAINT investigation_items_importance_check CHECK (length(trim(importance)) BETWEEN 1 AND 4000),
    ADD CONSTRAINT investigation_items_priority_check CHECK (priority IN ('high', 'medium', 'low')),
    ADD CONSTRAINT investigation_items_context_check CHECK (length(trim(relevant_context)) BETWEEN 1 AND 4000),
    ADD CONSTRAINT investigation_items_id_mission_org_unique
        UNIQUE (id, interview_mission_id, organisation_id),
    ADD CONSTRAINT investigation_items_organisation_fk FOREIGN KEY (organisation_id)
        REFERENCES organisations(id) ON DELETE CASCADE,
    ADD CONSTRAINT investigation_items_mission_org_fk FOREIGN KEY (interview_mission_id, organisation_id)
        REFERENCES interview_missions(id, organisation_id) ON DELETE CASCADE;

DROP POLICY item_isolation ON investigation_items;
CREATE POLICY item_isolation ON investigation_items
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

ALTER TABLE interview_mission_proposals
    ADD COLUMN confirmed_at timestamptz,
    DROP CONSTRAINT interview_mission_proposals_status_check,
    ADD CONSTRAINT interview_mission_proposals_status_check
        CHECK (status IN ('awaiting_confirmation', 'confirmed'));

CREATE TABLE mission_contexts (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    visibility text NOT NULL CHECK (visibility IN ('shared', 'private')),
    content text NOT NULL CHECK (length(trim(content)) BETWEEN 1 AND 4000),
    UNIQUE (interview_mission_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (interview_mission_id, organisation_id)
        REFERENCES interview_missions(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE mission_boundaries (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    boundary_kind text NOT NULL CHECK (boundary_kind IN ('boundary', 'prohibited_topic')),
    content text NOT NULL CHECK (length(trim(content)) BETWEEN 1 AND 4000),
    UNIQUE (interview_mission_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (interview_mission_id, organisation_id)
        REFERENCES interview_missions(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE mission_terms (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    term text NOT NULL CHECK (length(trim(term)) BETWEEN 1 AND 300),
    meaning text NOT NULL CHECK (length(trim(meaning)) BETWEEN 1 AND 2000),
    UNIQUE (interview_mission_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (interview_mission_id, organisation_id)
        REFERENCES interview_missions(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE mission_opening_questions (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    question text NOT NULL CHECK (length(trim(question)) BETWEEN 1 AND 2000),
    UNIQUE (interview_mission_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (interview_mission_id, organisation_id)
        REFERENCES interview_missions(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE mission_allowed_outcomes (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    outcome_kind text NOT NULL CHECK (outcome_kind IN (
        'supported_knowledge', 'unknown', 'conflict', 'ownership_gap'
    )),
    UNIQUE (investigation_item_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (interview_mission_id, organisation_id)
        REFERENCES interview_missions(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (investigation_item_id, interview_mission_id, organisation_id)
        REFERENCES investigation_items(id, interview_mission_id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE mission_ambiguities (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    content text NOT NULL CHECK (length(trim(content)) BETWEEN 1 AND 4000),
    represented_by_item_id uuid,
    UNIQUE (interview_mission_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (interview_mission_id, organisation_id)
        REFERENCES interview_missions(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (represented_by_item_id, interview_mission_id, organisation_id)
        REFERENCES investigation_items(id, interview_mission_id, organisation_id)
);

CREATE TABLE mission_element_provenance (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    element_kind text NOT NULL CHECK (element_kind IN (
        'objective', 'desired_outcome', 'intended_interviewee', 'interviewee_relevance',
        'completion_criteria', 'expected_commitment', 'data_use_summary', 'context', 'boundary',
        'term', 'opening_question', 'investigation_item', 'allowed_outcome', 'ambiguity'
    )),
    element_id uuid NOT NULL,
    source_kind text NOT NULL CHECK (source_kind IN (
        'investigator_message', 'agent_proposal_confirmed', 'investigator_edit'
    )),
    source_proposal_id uuid,
    shaping_session_id uuid,
    source_message_id uuid,
    source_runtime_work_id uuid,
    actor_membership_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((source_kind = 'investigator_message' AND source_proposal_id IS NOT NULL
            AND shaping_session_id IS NOT NULL AND source_message_id IS NOT NULL
            AND source_runtime_work_id IS NULL AND actor_membership_id IS NULL)
        OR (source_kind = 'agent_proposal_confirmed' AND source_proposal_id IS NOT NULL
            AND shaping_session_id IS NOT NULL AND source_message_id IS NULL
            AND source_runtime_work_id IS NOT NULL AND actor_membership_id IS NOT NULL)
        OR (source_kind = 'investigator_edit' AND source_proposal_id IS NULL
            AND shaping_session_id IS NULL AND source_message_id IS NULL
            AND source_runtime_work_id IS NULL AND actor_membership_id IS NOT NULL)),
    FOREIGN KEY (interview_mission_id, organisation_id)
        REFERENCES interview_missions(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (source_proposal_id, organisation_id)
        REFERENCES interview_mission_proposals(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (source_message_id, shaping_session_id, organisation_id)
        REFERENCES discovery_shaping_messages(id, shaping_session_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (source_runtime_work_id, shaping_session_id, organisation_id)
        REFERENCES shaping_runtime_work(id, shaping_session_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (actor_membership_id, organisation_id)
        REFERENCES memberships(id, organisation_id)
);

CREATE INDEX interview_missions_lineage_version_idx ON interview_missions(lineage_id, version DESC);
CREATE INDEX mission_contexts_mission_position_idx ON mission_contexts(interview_mission_id, position);
CREATE INDEX mission_boundaries_mission_position_idx ON mission_boundaries(interview_mission_id, position);
CREATE INDEX mission_terms_mission_position_idx ON mission_terms(interview_mission_id, position);
CREATE INDEX mission_questions_mission_position_idx ON mission_opening_questions(interview_mission_id, position);
CREATE INDEX mission_outcomes_item_position_idx ON mission_allowed_outcomes(investigation_item_id, position);
CREATE INDEX mission_ambiguities_mission_position_idx ON mission_ambiguities(interview_mission_id, position);
CREATE INDEX mission_provenance_element_idx
    ON mission_element_provenance(interview_mission_id, element_kind, element_id);

ALTER TABLE mission_contexts ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_contexts FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_boundaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_boundaries FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_terms ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_terms FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_opening_questions ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_opening_questions FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_allowed_outcomes ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_allowed_outcomes FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_ambiguities ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_ambiguities FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_element_provenance ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_element_provenance FORCE ROW LEVEL SECURITY;

CREATE POLICY mission_context_isolation ON mission_contexts
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_boundary_isolation ON mission_boundaries
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_term_isolation ON mission_terms
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_question_isolation ON mission_opening_questions
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_outcome_isolation ON mission_allowed_outcomes
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_ambiguity_isolation ON mission_ambiguities
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_provenance_isolation ON mission_element_provenance
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

REVOKE UPDATE, DELETE ON interview_missions FROM findworks_application;
GRANT UPDATE (status, approved_at, superseded_at) ON interview_missions TO findworks_application;
REVOKE UPDATE, DELETE ON investigation_items FROM findworks_application;
GRANT SELECT, INSERT ON mission_contexts TO findworks_application;
GRANT SELECT, INSERT ON mission_boundaries TO findworks_application;
GRANT SELECT, INSERT ON mission_terms TO findworks_application;
GRANT SELECT, INSERT ON mission_opening_questions TO findworks_application;
GRANT SELECT, INSERT ON mission_allowed_outcomes TO findworks_application;
GRANT SELECT, INSERT ON mission_ambiguities TO findworks_application;
GRANT SELECT, INSERT ON mission_element_provenance TO findworks_application;
GRANT UPDATE (status, confirmed_at) ON interview_mission_proposals TO findworks_application;
