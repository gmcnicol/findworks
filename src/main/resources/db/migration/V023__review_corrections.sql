ALTER TABLE interview_runtime_runs
    DROP CONSTRAINT interview_runtime_runs_source_check,
    ADD CONSTRAINT interview_runtime_runs_source_check CHECK (
        (work_kind = 'interview_turn' AND (
            (trigger IN ('session_start', 'resume') AND evidence_id IS NULL AND source_question_id IS NULL)
            OR (trigger = 'accepted_evidence' AND evidence_id IS NOT NULL AND source_question_id IS NULL)
            OR (trigger = 'clarification_request' AND evidence_id IS NULL AND source_question_id IS NOT NULL)))
        OR (work_kind = 'findings_extraction' AND trigger = 'findings_extraction'
            AND evidence_id IS NULL AND source_question_id IS NULL)
    );

ALTER TABLE interview_runtime_runs
    DROP CONSTRAINT interview_runtime_runs_evidence_unique,
    ADD CONSTRAINT interview_runtime_runs_evidence_generation_unique
        UNIQUE (evidence_id, generation);

CREATE TABLE m0_scripted_check_evidence (
    organisation_id uuid NOT NULL,
    scripted_run_id uuid NOT NULL,
    check_id text NOT NULL CHECK (length(trim(check_id)) BETWEEN 1 AND 100),
    outcome text NOT NULL CHECK (outcome = 'passed'),
    evidence_locator text NOT NULL CHECK (length(trim(evidence_locator)) BETWEEN 1 AND 500),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (scripted_run_id, check_id),
    FOREIGN KEY (scripted_run_id, organisation_id)
        REFERENCES m0_scripted_acceptance_runs(id, organisation_id)
);

CREATE TABLE m0_story_traceability (
    organisation_id uuid NOT NULL,
    scripted_run_id uuid NOT NULL,
    story_id integer NOT NULL CHECK (story_id BETWEEN 1 AND 90),
    acceptance_criterion text NOT NULL CHECK (acceptance_criterion IN (
        'AC35-1', 'AC35-2', 'AC35-3', 'AC35-4',
        'AC35-5', 'AC35-6', 'AC35-7', 'AC35-8'
    )),
    check_id text NOT NULL CHECK (length(trim(check_id)) BETWEEN 1 AND 100),
    evidence_locator text NOT NULL CHECK (length(trim(evidence_locator)) BETWEEN 1 AND 500),
    evidence_kind text NOT NULL CHECK (evidence_kind IN ('scripted', 'live')),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (scripted_run_id, story_id),
    FOREIGN KEY (scripted_run_id, organisation_id)
        REFERENCES m0_scripted_acceptance_runs(id, organisation_id)
);

CREATE TABLE m0_live_story_evidence (
    organisation_id uuid NOT NULL,
    run_id uuid NOT NULL,
    story_id integer NOT NULL CHECK (story_id BETWEEN 1 AND 90),
    evidence_locator text NOT NULL CHECK (length(trim(evidence_locator)) BETWEEN 1 AND 500),
    recorded_at timestamptz NOT NULL,
    PRIMARY KEY (run_id, story_id),
    FOREIGN KEY (run_id, organisation_id)
        REFERENCES m0_acceptance_runs(id, organisation_id)
);

CREATE TRIGGER m0_scripted_check_evidence_immutable
    BEFORE UPDATE OR DELETE ON m0_scripted_check_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_m0_acceptance_mutation();
CREATE TRIGGER m0_story_traceability_immutable
    BEFORE UPDATE OR DELETE ON m0_story_traceability
    FOR EACH ROW EXECUTE FUNCTION reject_m0_acceptance_mutation();
CREATE TRIGGER m0_live_story_evidence_immutable
    BEFORE UPDATE OR DELETE ON m0_live_story_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_m0_acceptance_mutation();

ALTER TABLE m0_scripted_check_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE m0_scripted_check_evidence FORCE ROW LEVEL SECURITY;
ALTER TABLE m0_story_traceability ENABLE ROW LEVEL SECURITY;
ALTER TABLE m0_story_traceability FORCE ROW LEVEL SECURITY;
ALTER TABLE m0_live_story_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE m0_live_story_evidence FORCE ROW LEVEL SECURITY;

CREATE POLICY m0_scripted_check_evidence_isolation ON m0_scripted_check_evidence
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY m0_story_traceability_isolation ON m0_story_traceability
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY m0_live_story_evidence_isolation ON m0_live_story_evidence
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

GRANT SELECT, INSERT ON m0_scripted_check_evidence, m0_story_traceability,
    m0_live_story_evidence TO findworks_support;
