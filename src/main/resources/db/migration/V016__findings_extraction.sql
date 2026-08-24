ALTER TABLE interview_runtime_runs
    ADD COLUMN work_kind text NOT NULL DEFAULT 'interview_turn',
    ADD COLUMN generation integer NOT NULL DEFAULT 1 CHECK (generation > 0),
    DROP CONSTRAINT interview_runtime_runs_trigger_check,
    DROP CONSTRAINT interview_runtime_runs_source_check,
    ADD CONSTRAINT interview_runtime_runs_kind_check
        CHECK (work_kind IN ('interview_turn', 'findings_extraction')),
    ADD CONSTRAINT interview_runtime_runs_trigger_check
        CHECK (trigger IN (
            'session_start', 'accepted_evidence', 'clarification_request', 'resume',
            'findings_extraction'
        )),
    ADD CONSTRAINT interview_runtime_runs_source_check CHECK (
        (work_kind = 'interview_turn' AND generation = 1 AND (
            (trigger IN ('session_start', 'resume') AND evidence_id IS NULL AND source_question_id IS NULL)
            OR (trigger = 'accepted_evidence' AND evidence_id IS NOT NULL AND source_question_id IS NULL)
            OR (trigger = 'clarification_request' AND evidence_id IS NULL AND source_question_id IS NOT NULL)))
        OR (work_kind = 'findings_extraction' AND trigger = 'findings_extraction'
            AND evidence_id IS NULL AND source_question_id IS NULL)
    );

DO $$
DECLARE
    name text;
BEGIN
    FOR name IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'interview_runtime_runs'::regclass
          AND contype = 'u'
          AND pg_get_constraintdef(oid) =
              'UNIQUE (interview_session_id, trigger, expected_revision)'
    LOOP
        EXECUTE format('ALTER TABLE interview_runtime_runs DROP CONSTRAINT %I', name);
    END LOOP;
END $$;

ALTER TABLE interview_runtime_runs
    ADD CONSTRAINT interview_runtime_runs_generation_unique
        UNIQUE (interview_session_id, trigger, expected_revision, generation);

ALTER TABLE runtime_credentials
    DROP CONSTRAINT runtime_credentials_operation_check,
    ADD CONSTRAINT runtime_credentials_operation_check
        CHECK (operation IN ('submit_interview_turn', 'submit_findings_package'));

CREATE TABLE findings_packages (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (interview_session_id),
    UNIQUE (id, organisation_id),
    UNIQUE (id, interview_session_id, interview_mission_id, organisation_id),
    FOREIGN KEY (interview_session_id, interview_mission_id, discovery_id, organisation_id)
        REFERENCES interview_sessions(id, interview_mission_id, discovery_id, organisation_id)
        ON DELETE CASCADE
);

CREATE TABLE findings_package_versions (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    findings_package_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    runtime_run_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (findings_package_id, version),
    UNIQUE (runtime_run_id),
    UNIQUE (id, organisation_id),
    UNIQUE (id, interview_session_id, interview_mission_id, organisation_id),
    FOREIGN KEY (findings_package_id, interview_session_id, interview_mission_id, organisation_id)
        REFERENCES findings_packages(id, interview_session_id, interview_mission_id, organisation_id)
        ON DELETE CASCADE,
    FOREIGN KEY (runtime_run_id, interview_session_id, organisation_id)
        REFERENCES interview_runtime_runs(id, interview_session_id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE findings_package_results (
    organisation_id uuid NOT NULL,
    findings_package_version_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    required boolean NOT NULL,
    PRIMARY KEY (findings_package_version_id, investigation_item_id),
    UNIQUE (findings_package_version_id, position),
    FOREIGN KEY (findings_package_version_id, interview_session_id, interview_mission_id, organisation_id)
        REFERENCES findings_package_versions(id, interview_session_id, interview_mission_id, organisation_id)
        ON DELETE CASCADE,
    FOREIGN KEY (investigation_item_id, interview_mission_id, organisation_id)
        REFERENCES investigation_items(id, interview_mission_id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE knowledge_items (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    findings_package_id uuid NOT NULL,
    current_version integer NOT NULL DEFAULT 1 CHECK (current_version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, organisation_id),
    UNIQUE (id, investigation_item_id, interview_mission_id, interview_session_id, organisation_id),
    FOREIGN KEY (findings_package_id, interview_session_id, interview_mission_id, organisation_id)
        REFERENCES findings_packages(id, interview_session_id, interview_mission_id, organisation_id)
        ON DELETE CASCADE,
    FOREIGN KEY (investigation_item_id, interview_mission_id, organisation_id)
        REFERENCES investigation_items(id, interview_mission_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (discovery_id, organisation_id)
        REFERENCES discoveries(id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE knowledge_item_versions (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    knowledge_item_id uuid NOT NULL,
    findings_package_version_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    category text NOT NULL CHECK (category IN (
        'fact', 'rule', 'decision', 'term', 'exception', 'assumption'
    )),
    claim text NOT NULL CHECK (length(trim(claim)) BETWEEN 1 AND 4000),
    confirmation_state text NOT NULL CHECK (confirmation_state IN ('unreviewed', 'unconfirmed')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (category = 'assumption' OR confirmation_state = 'unreviewed'),
    CHECK (category <> 'assumption' OR confirmation_state = 'unconfirmed'),
    UNIQUE (knowledge_item_id, version),
    UNIQUE (id, organisation_id),
    UNIQUE (id, investigation_item_id, interview_mission_id, interview_session_id, organisation_id),
    FOREIGN KEY (knowledge_item_id, investigation_item_id, interview_mission_id,
                 interview_session_id, organisation_id)
        REFERENCES knowledge_items(id, investigation_item_id, interview_mission_id,
            interview_session_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (findings_package_version_id, interview_session_id, interview_mission_id, organisation_id)
        REFERENCES findings_package_versions(id, interview_session_id, interview_mission_id, organisation_id)
        ON DELETE CASCADE
);

CREATE TABLE knowledge_item_evidence_citations (
    organisation_id uuid NOT NULL,
    knowledge_item_version_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    evidence_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    start_offset integer NOT NULL CHECK (start_offset >= 0),
    end_offset integer NOT NULL CHECK (end_offset > start_offset),
    quotation text NOT NULL CHECK (length(quotation) > 0),
    PRIMARY KEY (knowledge_item_version_id, position),
    UNIQUE (knowledge_item_version_id, evidence_id, start_offset, end_offset),
    FOREIGN KEY (knowledge_item_version_id, investigation_item_id, interview_mission_id,
                 interview_session_id, organisation_id)
        REFERENCES knowledge_item_versions(id, investigation_item_id, interview_mission_id,
            interview_session_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (evidence_id, investigation_item_id, interview_mission_id,
                 interview_session_id, organisation_id)
        REFERENCES evidence(id, investigation_item_id, interview_mission_id,
            interview_session_id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE findings_package_unresolved_outcomes (
    organisation_id uuid NOT NULL,
    findings_package_version_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    investigation_item_id uuid NOT NULL,
    outcome_id uuid NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    kind text NOT NULL CHECK (kind IN ('unknown', 'conflict', 'ownership_gap')),
    summary text NOT NULL CHECK (length(trim(summary)) BETWEEN 1 AND 4000),
    PRIMARY KEY (findings_package_version_id, outcome_id),
    UNIQUE (findings_package_version_id, investigation_item_id, position),
    FOREIGN KEY (findings_package_version_id, interview_session_id, interview_mission_id, organisation_id)
        REFERENCES findings_package_versions(id, interview_session_id, interview_mission_id, organisation_id)
        ON DELETE CASCADE,
    FOREIGN KEY (outcome_id, investigation_item_id, interview_mission_id,
                 interview_session_id, organisation_id)
        REFERENCES investigation_outcomes(id, investigation_item_id, interview_mission_id,
            interview_session_id, organisation_id) ON DELETE CASCADE
);

CREATE TABLE knowledge_item_links (
    organisation_id uuid NOT NULL,
    findings_package_version_id uuid NOT NULL,
    source_knowledge_item_version_id uuid NOT NULL,
    target_knowledge_item_version_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('supports', 'qualifies')),
    PRIMARY KEY (findings_package_version_id, source_knowledge_item_version_id,
                 target_knowledge_item_version_id, kind),
    CHECK (source_knowledge_item_version_id <> target_knowledge_item_version_id),
    FOREIGN KEY (source_knowledge_item_version_id, organisation_id)
        REFERENCES knowledge_item_versions(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (target_knowledge_item_version_id, organisation_id)
        REFERENCES knowledge_item_versions(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (findings_package_version_id, organisation_id)
        REFERENCES findings_package_versions(id, organisation_id) ON DELETE CASCADE
);

CREATE INDEX findings_package_session_idx ON findings_package_versions(interview_session_id, version DESC);
CREATE INDEX findings_result_item_idx ON findings_package_results(investigation_item_id);
CREATE INDEX knowledge_item_package_idx ON knowledge_items(findings_package_id, investigation_item_id);
CREATE INDEX knowledge_citation_evidence_idx ON knowledge_item_evidence_citations(evidence_id);
CREATE INDEX findings_unresolved_outcome_idx ON findings_package_unresolved_outcomes(outcome_id);

ALTER TABLE findings_packages ENABLE ROW LEVEL SECURITY;
ALTER TABLE findings_packages FORCE ROW LEVEL SECURITY;
ALTER TABLE findings_package_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE findings_package_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE findings_package_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE findings_package_results FORCE ROW LEVEL SECURITY;
ALTER TABLE knowledge_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_items FORCE ROW LEVEL SECURITY;
ALTER TABLE knowledge_item_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_item_versions FORCE ROW LEVEL SECURITY;
ALTER TABLE knowledge_item_evidence_citations ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_item_evidence_citations FORCE ROW LEVEL SECURITY;
ALTER TABLE findings_package_unresolved_outcomes ENABLE ROW LEVEL SECURITY;
ALTER TABLE findings_package_unresolved_outcomes FORCE ROW LEVEL SECURITY;
ALTER TABLE knowledge_item_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_item_links FORCE ROW LEVEL SECURITY;

CREATE POLICY findings_package_isolation ON findings_packages
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY findings_package_version_isolation ON findings_package_versions
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY findings_package_result_isolation ON findings_package_results
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY knowledge_item_isolation ON knowledge_items
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY knowledge_item_version_isolation ON knowledge_item_versions
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY knowledge_citation_isolation ON knowledge_item_evidence_citations
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY findings_unresolved_isolation ON findings_package_unresolved_outcomes
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY knowledge_link_isolation ON knowledge_item_links
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

GRANT SELECT, INSERT ON findings_packages, findings_package_versions, findings_package_results,
    knowledge_items, knowledge_item_versions, knowledge_item_evidence_citations,
    findings_package_unresolved_outcomes, knowledge_item_links TO findworks_application;

REVOKE UPDATE ON interview_runtime_runs FROM findworks_application;
GRANT UPDATE (status, attempts, available_at, lease_until, error_code, updated_at,
    cancelled_at, lease_owner, model_attempts, process_restarts, runtime_version)
    ON interview_runtime_runs TO findworks_application;
