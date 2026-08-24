ALTER TABLE evidence
    ADD COLUMN actor_membership_id uuid,
    ADD COLUMN corrects_evidence_id uuid,
    ALTER COLUMN participant_id DROP NOT NULL,
    DROP CONSTRAINT evidence_source_type_check,
    ADD CONSTRAINT evidence_source_type_check CHECK (
        (source_type = 'legacy' AND question_id IS NULL AND revises_evidence_id IS NULL
            AND actor_membership_id IS NULL AND corrects_evidence_id IS NULL)
        OR (source_type = 'interviewee_answer' AND question_id IS NOT NULL
            AND revises_evidence_id IS NULL AND participant_id IS NOT NULL
            AND actor_membership_id IS NULL AND corrects_evidence_id IS NULL)
        OR (source_type = 'interviewee_answer_revision' AND question_id IS NOT NULL
            AND revises_evidence_id IS NOT NULL AND participant_id IS NOT NULL
            AND actor_membership_id IS NULL AND corrects_evidence_id IS NULL)
        OR (source_type = 'investigator_correction' AND question_id IS NOT NULL
            AND revises_evidence_id IS NULL AND participant_id IS NULL
            AND actor_membership_id IS NOT NULL AND corrects_evidence_id IS NOT NULL)
    ),
    ADD CONSTRAINT evidence_actor_membership_scope_fk
        FOREIGN KEY (actor_membership_id, organisation_id)
        REFERENCES memberships(id, organisation_id),
    ADD CONSTRAINT evidence_correction_source_scope_fk
        FOREIGN KEY (corrects_evidence_id, investigation_item_id, interview_mission_id,
                     interview_session_id, organisation_id)
        REFERENCES evidence(id, investigation_item_id, interview_mission_id,
            interview_session_id, organisation_id);

CREATE INDEX evidence_actor_membership_idx ON evidence(actor_membership_id)
    WHERE actor_membership_id IS NOT NULL;
CREATE INDEX evidence_corrects_idx ON evidence(corrects_evidence_id)
    WHERE corrects_evidence_id IS NOT NULL;

UPDATE knowledge_item_versions
SET confirmation_state = 'unreviewed'
WHERE confirmation_state = 'unconfirmed';

DO $$
DECLARE
    name text;
BEGIN
    FOR name IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'knowledge_item_versions'::regclass
          AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%confirmation_state%'
    LOOP
        EXECUTE format('ALTER TABLE knowledge_item_versions DROP CONSTRAINT %I', name);
    END LOOP;
END $$;

ALTER TABLE knowledge_item_versions
    ADD COLUMN previous_version_id uuid,
    ADD COLUMN correction_evidence_id uuid,
    ADD CONSTRAINT knowledge_item_versions_review_state_check
        CHECK (confirmation_state IN ('unreviewed', 'accepted', 'corrected', 'rejected')),
    ADD CONSTRAINT knowledge_item_versions_lineage_shape_check CHECK (
        (version = 1 AND previous_version_id IS NULL AND correction_evidence_id IS NULL)
        OR (version > 1 AND previous_version_id IS NOT NULL AND correction_evidence_id IS NOT NULL)
    ),
    ADD CONSTRAINT knowledge_item_versions_id_item_org_unique
        UNIQUE (id, knowledge_item_id, organisation_id),
    ADD CONSTRAINT knowledge_item_versions_id_item_package_org_unique
        UNIQUE (id, knowledge_item_id, findings_package_version_id, organisation_id),
    ADD CONSTRAINT knowledge_item_versions_previous_scope_fk
        FOREIGN KEY (previous_version_id, knowledge_item_id, organisation_id)
        REFERENCES knowledge_item_versions(id, knowledge_item_id, organisation_id),
    ADD CONSTRAINT knowledge_item_versions_correction_evidence_scope_fk
        FOREIGN KEY (correction_evidence_id, investigation_item_id, interview_mission_id,
                     interview_session_id, organisation_id)
        REFERENCES evidence(id, investigation_item_id, interview_mission_id,
            interview_session_id, organisation_id);

ALTER TABLE findings_package_unresolved_outcomes
    ADD CONSTRAINT findings_unresolved_version_outcome_org_unique
        UNIQUE (findings_package_version_id, outcome_id, organisation_id);

CREATE TABLE findings_package_reviews (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    findings_package_version_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    review_revision integer NOT NULL DEFAULT 0 CHECK (review_revision >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (findings_package_version_id),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (findings_package_version_id, interview_session_id,
                 interview_mission_id, organisation_id)
        REFERENCES findings_package_versions(id, interview_session_id,
            interview_mission_id, organisation_id) ON DELETE CASCADE
);

INSERT INTO findings_package_reviews (
    id, organisation_id, findings_package_version_id, interview_session_id, interview_mission_id
)
SELECT gen_random_uuid(), organisation_id, id, interview_session_id, interview_mission_id
FROM findings_package_versions;

CREATE TABLE knowledge_item_reviews (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    findings_package_version_id uuid NOT NULL,
    knowledge_item_id uuid NOT NULL,
    knowledge_item_version_id uuid NOT NULL,
    state text NOT NULL CHECK (state IN ('accepted', 'corrected', 'rejected')),
    actor_membership_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (findings_package_version_id, knowledge_item_version_id),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (knowledge_item_version_id, knowledge_item_id,
                 findings_package_version_id, organisation_id)
        REFERENCES knowledge_item_versions(id, knowledge_item_id,
            findings_package_version_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (actor_membership_id, organisation_id)
        REFERENCES memberships(id, organisation_id)
);

CREATE TABLE findings_unresolved_outcome_reviews (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    findings_package_version_id uuid NOT NULL,
    outcome_id uuid NOT NULL,
    actor_membership_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (findings_package_version_id, outcome_id),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (findings_package_version_id, outcome_id, organisation_id)
        REFERENCES findings_package_unresolved_outcomes(
            findings_package_version_id, outcome_id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (actor_membership_id, organisation_id)
        REFERENCES memberships(id, organisation_id)
);

CREATE TABLE findings_package_decisions (
    id uuid PRIMARY KEY,
    organisation_id uuid NOT NULL,
    findings_package_review_id uuid NOT NULL,
    findings_package_version_id uuid NOT NULL,
    interview_session_id uuid NOT NULL,
    interview_mission_id uuid NOT NULL,
    decision text NOT NULL CHECK (decision IN ('accepted', 'rejected')),
    notes text NOT NULL CHECK (length(trim(notes)) BETWEEN 1 AND 4000),
    actor_membership_id uuid NOT NULL,
    review_revision integer NOT NULL CHECK (review_revision >= 0),
    decided_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (findings_package_version_id),
    UNIQUE (id, organisation_id),
    FOREIGN KEY (findings_package_review_id, organisation_id)
        REFERENCES findings_package_reviews(id, organisation_id) ON DELETE CASCADE,
    FOREIGN KEY (findings_package_version_id, interview_session_id, interview_mission_id, organisation_id)
        REFERENCES findings_package_versions(id, interview_session_id, interview_mission_id, organisation_id)
        ON DELETE CASCADE,
    FOREIGN KEY (actor_membership_id, organisation_id)
        REFERENCES memberships(id, organisation_id)
);

CREATE INDEX knowledge_item_reviews_package_idx
    ON knowledge_item_reviews(findings_package_version_id, knowledge_item_id);
CREATE INDEX findings_unresolved_reviews_package_idx
    ON findings_unresolved_outcome_reviews(findings_package_version_id, outcome_id);

ALTER TABLE findings_package_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE findings_package_reviews FORCE ROW LEVEL SECURITY;
ALTER TABLE knowledge_item_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_item_reviews FORCE ROW LEVEL SECURITY;
ALTER TABLE findings_unresolved_outcome_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE findings_unresolved_outcome_reviews FORCE ROW LEVEL SECURITY;
ALTER TABLE findings_package_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE findings_package_decisions FORCE ROW LEVEL SECURITY;

CREATE POLICY findings_package_review_isolation ON findings_package_reviews
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY knowledge_item_review_isolation ON knowledge_item_reviews
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY findings_unresolved_review_isolation ON findings_unresolved_outcome_reviews
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);
CREATE POLICY findings_package_decision_isolation ON findings_package_decisions
    USING (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

GRANT SELECT, INSERT ON findings_package_reviews, knowledge_item_reviews,
    findings_unresolved_outcome_reviews, findings_package_decisions TO findworks_application;
GRANT UPDATE (review_revision) ON findings_package_reviews TO findworks_application;
GRANT UPDATE (current_version) ON knowledge_items TO findworks_application;
GRANT UPDATE (confirmation_state) ON knowledge_item_versions TO findworks_application;
