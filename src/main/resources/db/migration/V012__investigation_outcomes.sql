ALTER TABLE evidence
    ADD COLUMN participation_signal text
        CHECK (participation_signal IS NULL OR participation_signal IN (
            'did_not_know', 'declined', 'other_owner'
        )),
    ADD CONSTRAINT evidence_participation_signal_content_check CHECK (
        participation_signal IS NULL
        OR (participation_signal = 'did_not_know' AND answer = 'I do not know.')
        OR (participation_signal = 'declined' AND answer = 'I prefer not to answer.')
        OR (participation_signal = 'other_owner' AND answer LIKE 'Someone else may know: %')
    ),
    ADD CONSTRAINT evidence_id_item_mission_session_org_unique
        UNIQUE (id, investigation_item_id, interview_mission_id, interview_session_id, organisation_id);

ALTER TABLE investigation_results
    DROP CONSTRAINT investigation_results_status_check,
    ADD CONSTRAINT investigation_results_status_check
        CHECK (status IN ('exploring', 'explicit_outcome')),
    ADD CONSTRAINT investigation_results_scope_unique
        UNIQUE (id, investigation_item_id, interview_mission_id, interview_session_id, organisation_id),
    ADD CONSTRAINT investigation_results_discovery_scope_unique
        UNIQUE (id, investigation_item_id, interview_mission_id,
            interview_session_id, discovery_id, organisation_id);
GRANT UPDATE (status) ON investigation_results TO findworks_application;

ALTER TABLE mission_boundaries
    ADD CONSTRAINT mission_boundaries_mission_scope_unique
        UNIQUE (id, interview_mission_id, organisation_id);

ALTER TABLE interview_runtime_runs
    ADD COLUMN source_question_id uuid,
    DROP CONSTRAINT interview_runtime_runs_trigger_check,
    DROP CONSTRAINT interview_runtime_runs_evidence_check,
    ADD CONSTRAINT interview_runtime_runs_trigger_check
        CHECK (trigger IN ('session_start', 'accepted_evidence', 'clarification_request')),
    ADD CONSTRAINT interview_runtime_runs_source_check CHECK (
        (trigger = 'session_start' AND evidence_id IS NULL AND source_question_id IS NULL)
        OR (trigger = 'accepted_evidence' AND evidence_id IS NOT NULL AND source_question_id IS NULL)
        OR (trigger = 'clarification_request' AND evidence_id IS NULL AND source_question_id IS NOT NULL)
    ),
    ADD CONSTRAINT interview_runtime_runs_source_question_scope_fk
        FOREIGN KEY (source_question_id, interview_session_id, organisation_id)
        REFERENCES interview_questions(id, interview_session_id, organisation_id) ON DELETE CASCADE;

ALTER TABLE interview_questions
    ADD COLUMN question_kind text NOT NULL DEFAULT 'ordinary'
        CHECK (question_kind IN ('ordinary', 'clarification', 'paraphrase_confirmation')),
    ADD COLUMN clarifies_question_id uuid,
    ADD COLUMN source_evidence_id uuid,
    ADD COLUMN paraphrase_reason text
        CHECK (paraphrase_reason IS NULL OR paraphrase_reason IN (
            'ambiguity', 'contradiction', 'inference', 'material_importance'
        )),
    ADD CONSTRAINT interview_questions_kind_link_check CHECK (
        (question_kind = 'ordinary' AND clarifies_question_id IS NULL
            AND source_evidence_id IS NULL AND paraphrase_reason IS NULL)
        OR (question_kind = 'clarification' AND clarifies_question_id IS NOT NULL
            AND source_evidence_id IS NULL AND paraphrase_reason IS NULL)
        OR (question_kind = 'paraphrase_confirmation' AND source_evidence_id IS NOT NULL
            AND paraphrase_reason IS NOT NULL)
    ),
    ADD CONSTRAINT interview_questions_clarifies_scope_fk
        FOREIGN KEY (clarifies_question_id, interview_session_id, organisation_id)
        REFERENCES interview_questions(id, interview_session_id, organisation_id) ON DELETE CASCADE,
    ADD CONSTRAINT interview_questions_source_evidence_scope_fk
        FOREIGN KEY (source_evidence_id, interview_session_id, organisation_id)
        REFERENCES evidence(id, interview_session_id, organisation_id) ON DELETE CASCADE;

CREATE TABLE investigation_outcomes (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    investigation_result_id uuid NOT NULL,
    runtime_run_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN (
        'supported_knowledge', 'assumption', 'unknown', 'conflict', 'ownership_gap'
    )),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, organisation_id),
    UNIQUE (id, investigation_item_id, interview_mission_id, interview_session_id, organisation_id),
    FOREIGN KEY (
        investigation_result_id, investigation_item_id, interview_mission_id,
        interview_session_id, discovery_id, organisation_id
    ) REFERENCES investigation_results(
        id, investigation_item_id, interview_mission_id, interview_session_id,
        discovery_id, organisation_id
    ) ON DELETE CASCADE,
    FOREIGN KEY (runtime_run_id, interview_session_id, organisation_id)
        REFERENCES interview_runtime_runs(id, interview_session_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (discovery_id, organisation_id)
        REFERENCES discoveries(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE investigation_outcome_evidence (
    organisation_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    outcome_id uuid NOT NULL,
    evidence_id uuid NOT NULL,
    PRIMARY KEY (outcome_id, evidence_id),
    FOREIGN KEY (
        outcome_id, investigation_item_id, interview_mission_id,
        interview_session_id, organisation_id
    ) REFERENCES investigation_outcomes(
        id, investigation_item_id, interview_mission_id, interview_session_id, organisation_id
    ) ON DELETE CASCADE,
    FOREIGN KEY (
        evidence_id, investigation_item_id, interview_mission_id,
        interview_session_id, organisation_id
    ) REFERENCES evidence(
        id, investigation_item_id, interview_mission_id, interview_session_id, organisation_id
    ) ON DELETE CASCADE
);

CREATE TABLE unknown_outcomes (
    outcome_id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    reason text NOT NULL CHECK (reason IN (
        'did_not_know', 'declined', 'evidence_insufficient', 'owner_unidentified'
    )),
    explanation text CHECK (explanation IS NULL OR length(trim(explanation)) BETWEEN 1 AND 2000),
    FOREIGN KEY (outcome_id, organisation_id)
        REFERENCES investigation_outcomes(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE ownership_gap_outcomes (
    outcome_id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    unresolved_subject text NOT NULL CHECK (length(trim(unresolved_subject)) BETWEEN 1 AND 2000),
    why_current_participant_cannot_answer text NOT NULL
        CHECK (length(trim(why_current_participant_cannot_answer)) BETWEEN 1 AND 2000),
    owner_name text CHECK (owner_name IS NULL OR length(trim(owner_name)) BETWEEN 1 AND 300),
    owner_description text CHECK (owner_description IS NULL OR length(trim(owner_description)) BETWEEN 1 AND 1000),
    CHECK (owner_name IS NOT NULL OR owner_description IS NOT NULL),
    FOREIGN KEY (outcome_id, organisation_id)
        REFERENCES investigation_outcomes(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE candidate_knowledge_claims (
    outcome_id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    knowledge_kind text NOT NULL CHECK (knowledge_kind IN (
        'fact', 'rule', 'decision', 'term', 'exception', 'assumption'
    )),
    claim text NOT NULL CHECK (length(trim(claim)) BETWEEN 1 AND 4000),
    confirmation_state text NOT NULL CHECK (confirmation_state IN ('confirmed', 'unconfirmed')),
    CHECK (knowledge_kind <> 'assumption' OR confirmation_state = 'unconfirmed'),
    FOREIGN KEY (outcome_id, organisation_id)
        REFERENCES investigation_outcomes(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE conflict_outcomes (
    outcome_id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    unresolved_explanation text NOT NULL
        CHECK (length(trim(unresolved_explanation)) BETWEEN 1 AND 4000),
    FOREIGN KEY (outcome_id, organisation_id)
        REFERENCES investigation_outcomes(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE conflict_members (
    organisation_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    conflict_outcome_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    claim text NOT NULL CHECK (length(trim(claim)) BETWEEN 1 AND 4000),
    evidence_id uuid NOT NULL,
    PRIMARY KEY (conflict_outcome_id, position),
    UNIQUE (conflict_outcome_id, evidence_id),
    FOREIGN KEY (
        conflict_outcome_id, investigation_item_id, interview_mission_id,
        interview_session_id, organisation_id
    ) REFERENCES investigation_outcomes(
        id, investigation_item_id, interview_mission_id, interview_session_id, organisation_id
    ) ON DELETE CASCADE,
    FOREIGN KEY (
        evidence_id, investigation_item_id, interview_mission_id,
        interview_session_id, organisation_id
    ) REFERENCES evidence(
        id, investigation_item_id, interview_mission_id, interview_session_id, organisation_id
    ) ON DELETE CASCADE
);

CREATE TABLE evidence_scope_assessments (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    evidence_id uuid NOT NULL UNIQUE,
    runtime_run_id uuid NOT NULL,
    assessment text NOT NULL CHECK (assessment IN ('in_scope', 'out_of_scope')),
    mission_boundary_id uuid,
    rationale text NOT NULL CHECK (length(trim(rationale)) BETWEEN 1 AND 2000),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((assessment = 'in_scope' AND mission_boundary_id IS NULL)
        OR (assessment = 'out_of_scope' AND mission_boundary_id IS NOT NULL)),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (
        evidence_id, investigation_item_id, interview_mission_id,
        interview_session_id, organisation_id
    ) REFERENCES evidence(
        id, investigation_item_id, interview_mission_id, interview_session_id, organisation_id
    ) ON DELETE CASCADE,
    FOREIGN KEY (runtime_run_id, interview_session_id, organisation_id)
        REFERENCES interview_runtime_runs(id, interview_session_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (mission_boundary_id, interview_mission_id, organisation_id)
        REFERENCES mission_boundaries(id, interview_mission_id, organisation_id) ON DELETE CASCADE
);

CREATE INDEX investigation_outcomes_result_idx ON investigation_outcomes(investigation_result_id, created_at);
CREATE INDEX investigation_outcome_evidence_evidence_idx ON investigation_outcome_evidence(evidence_id);
CREATE INDEX evidence_scope_assessments_session_idx ON evidence_scope_assessments(interview_session_id, assessment);

ALTER TABLE investigation_outcomes ENABLE ROW LEVEL SECURITY;
ALTER TABLE investigation_outcomes FORCE ROW LEVEL SECURITY;
ALTER TABLE investigation_outcome_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE investigation_outcome_evidence FORCE ROW LEVEL SECURITY;
ALTER TABLE unknown_outcomes ENABLE ROW LEVEL SECURITY;
ALTER TABLE unknown_outcomes FORCE ROW LEVEL SECURITY;
ALTER TABLE ownership_gap_outcomes ENABLE ROW LEVEL SECURITY;
ALTER TABLE ownership_gap_outcomes FORCE ROW LEVEL SECURITY;
ALTER TABLE candidate_knowledge_claims ENABLE ROW LEVEL SECURITY;
ALTER TABLE candidate_knowledge_claims FORCE ROW LEVEL SECURITY;
ALTER TABLE conflict_outcomes ENABLE ROW LEVEL SECURITY;
ALTER TABLE conflict_outcomes FORCE ROW LEVEL SECURITY;
ALTER TABLE conflict_members ENABLE ROW LEVEL SECURITY;
ALTER TABLE conflict_members FORCE ROW LEVEL SECURITY;
ALTER TABLE evidence_scope_assessments ENABLE ROW LEVEL SECURITY;
ALTER TABLE evidence_scope_assessments FORCE ROW LEVEL SECURITY;

CREATE POLICY investigation_outcome_isolation ON investigation_outcomes
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY investigation_outcome_evidence_isolation ON investigation_outcome_evidence
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY unknown_outcome_isolation ON unknown_outcomes
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY ownership_gap_outcome_isolation ON ownership_gap_outcomes
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY candidate_knowledge_claim_isolation ON candidate_knowledge_claims
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY conflict_outcome_isolation ON conflict_outcomes
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY conflict_member_isolation ON conflict_members
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY evidence_scope_assessment_isolation ON evidence_scope_assessments
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

GRANT SELECT, INSERT ON investigation_outcomes, investigation_outcome_evidence,
    unknown_outcomes, ownership_gap_outcomes, candidate_knowledge_claims,
    conflict_outcomes, conflict_members, evidence_scope_assessments TO findworks_application;
