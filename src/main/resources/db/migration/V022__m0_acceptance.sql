CREATE TABLE m0_scripted_acceptance_runs (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL REFERENCES organisations(id),
    operator_id uuid NOT NULL,
    release_digest text NOT NULL CHECK (release_digest ~ '^sha256:[a-f0-9]{64}$'),
    git_commit text NOT NULL CHECK (git_commit ~ '^[a-f0-9]{40}$'),
    migration_digest text NOT NULL CHECK (migration_digest ~ '^sha256:[a-f0-9]{64}$'),
    pi_runtime_digest text NOT NULL CHECK (pi_runtime_digest ~ '^sha256:[a-f0-9]{64}$'),
    skill_digest text NOT NULL CHECK (skill_digest ~ '^sha256:[a-f0-9]{64}$'),
    extension_digest text NOT NULL CHECK (extension_digest ~ '^sha256:[a-f0-9]{64}$'),
    provider_alias text NOT NULL CHECK (length(provider_alias) BETWEEN 1 AND 100),
    model_alias text NOT NULL CHECK (length(model_alias) BETWEEN 1 AND 100),
    traceability_digest text NOT NULL CHECK (traceability_digest ~ '^sha256:[a-f0-9]{64}$'),
    results_digest text NOT NULL CHECK (results_digest ~ '^sha256:[a-f0-9]{64}$'),
    outcome text NOT NULL CHECK (outcome IN ('passed', 'failed')),
    safe_failure_class text CHECK (safe_failure_class IS NULL OR safe_failure_class IN (
        'missing_check', 'failed_check', 'stale_evidence', 'release_mismatch',
        'restore_unverified', 'content_leak'
    )),
    completed_at timestamptz NOT NULL,
    CHECK ((outcome = 'failed') = (safe_failure_class IS NOT NULL)),
    UNIQUE (id, organisation_id)
);

CREATE TABLE m0_acceptance_runs (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL REFERENCES organisations(id),
    operator_id uuid NOT NULL,
    scripted_run_id uuid NOT NULL,
    release_digest text NOT NULL CHECK (release_digest ~ '^sha256:[a-f0-9]{64}$'),
    traceability_digest text NOT NULL CHECK (traceability_digest ~ '^sha256:[a-f0-9]{64}$'),
    restore_drill_id uuid NOT NULL,
    opened_at timestamptz NOT NULL,
    UNIQUE (id, organisation_id),
    FOREIGN KEY (scripted_run_id, organisation_id)
        REFERENCES m0_scripted_acceptance_runs(id, organisation_id)
);

CREATE TABLE m0_acceptance_bindings (
    run_id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    discovery_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    findings_package_version_id uuid NOT NULL,
    investigator_membership_id uuid NOT NULL,
    participant_id uuid NOT NULL,
    observer_id uuid NOT NULL,
    shaping_adaptive boolean NOT NULL,
    interview_adaptive boolean NOT NULL,
    no_fabrication boolean NOT NULL,
    accessibility_passed boolean NOT NULL,
    recorded_at timestamptz NOT NULL,
    FOREIGN KEY (run_id, organisation_id)
        REFERENCES m0_acceptance_runs(id, organisation_id)
);

CREATE TABLE m0_acceptance_results (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    run_id uuid NOT NULL,
    criterion text NOT NULL CHECK (criterion IN (
        'AC35-1', 'AC35-2', 'AC35-3', 'AC35-4', 'AC35-5',
        'AC35-6', 'AC35-7', 'AC35-8', 'AC35-FINAL'
    )),
    outcome text NOT NULL CHECK (outcome IN ('passed', 'failed')),
    safe_failure_class text CHECK (safe_failure_class IS NULL OR safe_failure_class IN (
        'journey_incomplete', 'adaptation_failed', 'record_incomplete',
        'scripted_failure', 'automatic_failure', 'accessibility_blocker',
        'traceability_gap', 'package_not_accepted'
    )),
    actor_kind text NOT NULL CHECK (actor_kind IN ('system', 'human')),
    recorded_at timestamptz NOT NULL,
    CHECK ((outcome = 'failed') = (safe_failure_class IS NOT NULL)),
    UNIQUE (run_id, criterion),
    FOREIGN KEY (run_id, organisation_id)
        REFERENCES m0_acceptance_runs(id, organisation_id)
);

CREATE OR REPLACE FUNCTION reject_m0_acceptance_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'M0 acceptance records are append-only';
END
$$;

CREATE TRIGGER m0_scripted_acceptance_immutable
    BEFORE UPDATE OR DELETE ON m0_scripted_acceptance_runs
    FOR EACH ROW EXECUTE FUNCTION reject_m0_acceptance_mutation();
CREATE TRIGGER m0_acceptance_runs_immutable
    BEFORE UPDATE OR DELETE ON m0_acceptance_runs
    FOR EACH ROW EXECUTE FUNCTION reject_m0_acceptance_mutation();
CREATE TRIGGER m0_acceptance_bindings_immutable
    BEFORE UPDATE OR DELETE ON m0_acceptance_bindings
    FOR EACH ROW EXECUTE FUNCTION reject_m0_acceptance_mutation();
CREATE TRIGGER m0_acceptance_results_immutable
    BEFORE UPDATE OR DELETE ON m0_acceptance_results
    FOR EACH ROW EXECUTE FUNCTION reject_m0_acceptance_mutation();

ALTER TABLE m0_scripted_acceptance_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE m0_scripted_acceptance_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE m0_acceptance_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE m0_acceptance_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE m0_acceptance_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE m0_acceptance_bindings FORCE ROW LEVEL SECURITY;
ALTER TABLE m0_acceptance_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE m0_acceptance_results FORCE ROW LEVEL SECURITY;

CREATE POLICY m0_scripted_acceptance_isolation ON m0_scripted_acceptance_runs
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY m0_acceptance_run_isolation ON m0_acceptance_runs
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY m0_acceptance_binding_isolation ON m0_acceptance_bindings
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY m0_acceptance_result_isolation ON m0_acceptance_results
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

GRANT SELECT, INSERT ON m0_scripted_acceptance_runs, m0_acceptance_runs,
    m0_acceptance_bindings, m0_acceptance_results TO findworks_support;
GRANT SELECT ON organisations, memberships, interview_missions, investigation_items,
    discovery_participants, invitations, interview_sessions, interview_access_grants,
    evidence, investigation_results, investigation_outcomes, evidence_scope_assessments,
    interview_runtime_runs, interview_runtime_attempts, interview_questions,
    interview_application_events,
    interview_completion_proposals, findings_packages, findings_package_versions,
    findings_package_results, knowledge_items, knowledge_item_versions,
    knowledge_item_evidence_citations, findings_package_unresolved_outcomes,
    findings_package_reviews, knowledge_item_reviews, findings_unresolved_outcome_reviews,
    findings_package_decisions, recovery_drill_results, audit_records
    TO findworks_support;
GRANT SELECT ON flyway_schema_history TO findworks_support, findworks_application, findworks_worker;
