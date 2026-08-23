ALTER TABLE discovery_shaping_messages
    ADD CONSTRAINT shaping_messages_scope_unique
    UNIQUE (id, shaping_session_id, organisation_id);

ALTER TABLE shaping_runtime_work
    ADD CONSTRAINT shaping_work_scope_unique
    UNIQUE (id, shaping_session_id, organisation_id);

CREATE TABLE interview_mission_proposals (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    shaping_session_id uuid NOT NULL,
    runtime_work_id uuid NOT NULL,
    status text NOT NULL DEFAULT 'awaiting_confirmation'
        CHECK (status = 'awaiting_confirmation'),
    objective text NOT NULL CHECK (length(trim(objective)) BETWEEN 1 AND 4000),
    desired_outcome text NOT NULL CHECK (length(trim(desired_outcome)) BETWEEN 1 AND 4000),
    intended_interviewee text NOT NULL CHECK (length(trim(intended_interviewee)) BETWEEN 1 AND 1000),
    interviewee_relevance text NOT NULL CHECK (length(trim(interviewee_relevance)) BETWEEN 1 AND 4000),
    completion_criteria text NOT NULL CHECK (length(trim(completion_criteria)) BETWEEN 1 AND 4000),
    expected_commitment text NOT NULL CHECK (length(trim(expected_commitment)) BETWEEN 1 AND 1000),
    data_use_summary text NOT NULL CHECK (length(trim(data_use_summary)) BETWEEN 1 AND 4000),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (runtime_work_id),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (shaping_session_id, organisation_id)
        REFERENCES discovery_shaping_sessions(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (runtime_work_id, shaping_session_id, organisation_id)
        REFERENCES shaping_runtime_work(id, shaping_session_id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE mission_proposal_contexts (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    visibility text NOT NULL CHECK (visibility IN ('shared', 'private')),
    content text NOT NULL CHECK (length(trim(content)) BETWEEN 1 AND 4000),
    UNIQUE (proposal_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (proposal_id, organisation_id)
        REFERENCES interview_mission_proposals(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE mission_proposal_boundaries (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    boundary_kind text NOT NULL CHECK (boundary_kind IN ('boundary', 'prohibited_topic')),
    content text NOT NULL CHECK (length(trim(content)) BETWEEN 1 AND 4000),
    UNIQUE (proposal_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (proposal_id, organisation_id)
        REFERENCES interview_mission_proposals(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE mission_proposal_terms (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    term text NOT NULL CHECK (length(trim(term)) BETWEEN 1 AND 300),
    meaning text NOT NULL CHECK (length(trim(meaning)) BETWEEN 1 AND 2000),
    UNIQUE (proposal_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (proposal_id, organisation_id)
        REFERENCES interview_mission_proposals(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE mission_proposal_opening_questions (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    question text NOT NULL CHECK (length(trim(question)) BETWEEN 1 AND 2000),
    UNIQUE (proposal_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (proposal_id, organisation_id)
        REFERENCES interview_mission_proposals(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE mission_proposal_investigation_items (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    knowledge_gap text NOT NULL CHECK (length(trim(knowledge_gap)) BETWEEN 1 AND 4000),
    importance text NOT NULL CHECK (length(trim(importance)) BETWEEN 1 AND 4000),
    priority text NOT NULL CHECK (priority IN ('high', 'medium', 'low')),
    relevant_context text NOT NULL CHECK (length(trim(relevant_context)) BETWEEN 1 AND 4000),
    required boolean NOT NULL,
    UNIQUE (proposal_id, position),
    UNIQUE (id, proposal_id, organisation_id),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (proposal_id, organisation_id)
        REFERENCES interview_mission_proposals(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE mission_proposal_allowed_outcomes (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    outcome_kind text NOT NULL CHECK (outcome_kind IN (
        'supported_knowledge', 'unknown', 'conflict', 'ownership_gap'
    )),
    UNIQUE (investigation_item_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (proposal_id, organisation_id)
        REFERENCES interview_mission_proposals(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (investigation_item_id, proposal_id, organisation_id)
        REFERENCES mission_proposal_investigation_items(id, proposal_id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE mission_proposal_ambiguities (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    content text NOT NULL CHECK (length(trim(content)) BETWEEN 1 AND 4000),
    represented_by_item_id uuid,
    UNIQUE (proposal_id, position),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (proposal_id, organisation_id)
        REFERENCES interview_mission_proposals(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (represented_by_item_id, proposal_id, organisation_id)
        REFERENCES mission_proposal_investigation_items(id, proposal_id, organisation_id)
);

CREATE TABLE mission_proposal_provenance (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    proposal_id uuid NOT NULL,
    shaping_session_id uuid NOT NULL,
    element_kind text NOT NULL CHECK (element_kind IN (
        'objective', 'desired_outcome', 'intended_interviewee', 'interviewee_relevance',
        'completion_criteria', 'expected_commitment', 'data_use_summary', 'context', 'boundary',
        'term', 'opening_question', 'investigation_item', 'allowed_outcome', 'ambiguity'
    )),
    element_id uuid NOT NULL,
    source_kind text NOT NULL CHECK (source_kind IN ('investigator_message', 'agent_proposal')),
    source_message_id uuid,
    source_runtime_work_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((source_kind = 'investigator_message' AND source_message_id IS NOT NULL
            AND source_runtime_work_id IS NULL)
        OR (source_kind = 'agent_proposal' AND source_message_id IS NULL
            AND source_runtime_work_id IS NOT NULL)),
    UNIQUE NULLS NOT DISTINCT (
        proposal_id, element_kind, element_id, source_kind, source_message_id, source_runtime_work_id
    ),
    FOREIGN KEY (proposal_id, organisation_id)
        REFERENCES interview_mission_proposals(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (source_message_id, shaping_session_id, organisation_id)
        REFERENCES discovery_shaping_messages(id, shaping_session_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (source_runtime_work_id, shaping_session_id, organisation_id)
        REFERENCES shaping_runtime_work(id, shaping_session_id, organisation_id) ON DELETE CASCADE
);

CREATE INDEX mission_proposals_session_created_idx
    ON interview_mission_proposals(shaping_session_id, created_at DESC);
CREATE INDEX mission_proposal_contexts_proposal_idx ON mission_proposal_contexts(proposal_id, position);
CREATE INDEX mission_proposal_boundaries_proposal_idx ON mission_proposal_boundaries(proposal_id, position);
CREATE INDEX mission_proposal_terms_proposal_idx ON mission_proposal_terms(proposal_id, position);
CREATE INDEX mission_proposal_questions_proposal_idx ON mission_proposal_opening_questions(proposal_id, position);
CREATE INDEX mission_proposal_items_proposal_idx ON mission_proposal_investigation_items(proposal_id, position);
CREATE INDEX mission_proposal_outcomes_item_idx ON mission_proposal_allowed_outcomes(investigation_item_id, position);
CREATE INDEX mission_proposal_ambiguities_proposal_idx ON mission_proposal_ambiguities(proposal_id, position);
CREATE INDEX mission_proposal_provenance_element_idx
    ON mission_proposal_provenance(proposal_id, element_kind, element_id);

ALTER TABLE interview_mission_proposals ENABLE ROW LEVEL SECURITY;
ALTER TABLE interview_mission_proposals FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_contexts ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_contexts FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_boundaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_boundaries FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_terms ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_terms FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_opening_questions ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_opening_questions FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_investigation_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_investigation_items FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_allowed_outcomes ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_allowed_outcomes FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_ambiguities ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_ambiguities FORCE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_provenance ENABLE ROW LEVEL SECURITY;
ALTER TABLE mission_proposal_provenance FORCE ROW LEVEL SECURITY;

CREATE POLICY mission_proposal_isolation ON interview_mission_proposals
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_proposal_context_isolation ON mission_proposal_contexts
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_proposal_boundary_isolation ON mission_proposal_boundaries
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_proposal_term_isolation ON mission_proposal_terms
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_proposal_question_isolation ON mission_proposal_opening_questions
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_proposal_item_isolation ON mission_proposal_investigation_items
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_proposal_outcome_isolation ON mission_proposal_allowed_outcomes
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_proposal_ambiguity_isolation ON mission_proposal_ambiguities
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY mission_proposal_provenance_isolation ON mission_proposal_provenance
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

GRANT SELECT, INSERT ON interview_mission_proposals TO findworks_application;
GRANT SELECT, INSERT ON mission_proposal_contexts TO findworks_application;
GRANT SELECT, INSERT ON mission_proposal_boundaries TO findworks_application;
GRANT SELECT, INSERT ON mission_proposal_terms TO findworks_application;
GRANT SELECT, INSERT ON mission_proposal_opening_questions TO findworks_application;
GRANT SELECT, INSERT ON mission_proposal_investigation_items TO findworks_application;
GRANT SELECT, INSERT ON mission_proposal_allowed_outcomes TO findworks_application;
GRANT SELECT, INSERT ON mission_proposal_ambiguities TO findworks_application;
GRANT SELECT, INSERT ON mission_proposal_provenance TO findworks_application;
