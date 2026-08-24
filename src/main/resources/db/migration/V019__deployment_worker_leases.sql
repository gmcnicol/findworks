ALTER TABLE shaping_runtime_work
    ADD COLUMN lease_owner uuid,
    ADD COLUMN heartbeat_at timestamptz,
    ADD CONSTRAINT shaping_runtime_work_id_organisation_unique UNIQUE (id, organisation_id),
    ADD CONSTRAINT shaping_runtime_work_lease_check CHECK (
        (status = 'running' AND lease_until IS NOT NULL AND lease_owner IS NOT NULL)
        OR (status <> 'running' AND lease_until IS NULL AND lease_owner IS NULL)
    );

ALTER TABLE interview_runtime_runs ADD COLUMN heartbeat_at timestamptz;
ALTER TABLE invitation_delivery_jobs ADD COLUMN heartbeat_at timestamptz;
ALTER TABLE retention_warnings ADD COLUMN heartbeat_at timestamptz;
ALTER TABLE deletion_ledger ADD COLUMN heartbeat_at timestamptz;

CREATE TABLE pi_worker_slots (
    slot_number smallint PRIMARY KEY CHECK (slot_number BETWEEN 1 AND 2),
    organisation_id uuid,
    runtime_run_id uuid,
    shaping_work_id uuid,
    lease_owner uuid,
    lease_expires_at timestamptz,
    heartbeat_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((organisation_id IS NULL AND runtime_run_id IS NULL AND shaping_work_id IS NULL
                AND lease_owner IS NULL AND lease_expires_at IS NULL AND heartbeat_at IS NULL)
        OR (organisation_id IS NOT NULL AND lease_owner IS NOT NULL
                AND lease_expires_at IS NOT NULL AND heartbeat_at IS NOT NULL
                AND ((runtime_run_id IS NOT NULL) <> (shaping_work_id IS NOT NULL)))),
    UNIQUE (runtime_run_id),
    UNIQUE (shaping_work_id),
    FOREIGN KEY (runtime_run_id, organisation_id)
        REFERENCES interview_runtime_runs(id, organisation_id),
    FOREIGN KEY (shaping_work_id, organisation_id)
        REFERENCES shaping_runtime_work(id, organisation_id)
);

INSERT INTO pi_worker_slots (slot_number) VALUES (1), (2);

CREATE FUNCTION release_runtime_pi_worker_slot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE pi_worker_slots SET organisation_id = NULL, runtime_run_id = NULL,
        shaping_work_id = NULL, lease_owner = NULL, lease_expires_at = NULL,
        heartbeat_at = NULL, updated_at = now()
    WHERE runtime_run_id = OLD.id;
    RETURN OLD;
END
$$;

CREATE FUNCTION release_shaping_pi_worker_slot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE pi_worker_slots SET organisation_id = NULL, runtime_run_id = NULL,
        shaping_work_id = NULL, lease_owner = NULL, lease_expires_at = NULL,
        heartbeat_at = NULL, updated_at = now()
    WHERE shaping_work_id = OLD.id;
    RETURN OLD;
END
$$;

CREATE TRIGGER release_runtime_pi_worker_slot_before_delete
    BEFORE DELETE ON interview_runtime_runs
    FOR EACH ROW EXECUTE FUNCTION release_runtime_pi_worker_slot();
CREATE TRIGGER release_shaping_pi_worker_slot_before_delete
    BEFORE DELETE ON shaping_runtime_work
    FOR EACH ROW EXECUTE FUNCTION release_shaping_pi_worker_slot();

ALTER TABLE pi_worker_slots ENABLE ROW LEVEL SECURITY;
ALTER TABLE pi_worker_slots FORCE ROW LEVEL SECURITY;
CREATE POLICY pi_worker_slot_isolation ON pi_worker_slots
    USING (organisation_id IS NULL
        OR organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid)
    WITH CHECK (organisation_id IS NULL
        OR organisation_id = nullif(current_setting('findworks.organisation_id', true), '')::uuid);

CREATE FUNCTION require_worker_job_lease() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT pg_has_role(current_user, 'findworks_worker', 'USAGE')
            AND NEW.status IN ('running', 'leased') THEN
        RAISE EXCEPTION 'worker role required to own a job lease' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END
$$;

CREATE FUNCTION require_worker_purge_lease() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT pg_has_role(current_user, 'findworks_worker', 'USAGE') AND NEW.stage = 'purging' THEN
        RAISE EXCEPTION 'worker role required to own a purge lease' USING ERRCODE = '42501';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER shaping_work_requires_worker_lease
    BEFORE INSERT OR UPDATE ON shaping_runtime_work
    FOR EACH ROW EXECUTE FUNCTION require_worker_job_lease();
CREATE TRIGGER interview_run_requires_worker_lease
    BEFORE INSERT OR UPDATE ON interview_runtime_runs
    FOR EACH ROW EXECUTE FUNCTION require_worker_job_lease();
CREATE TRIGGER invitation_job_requires_worker_lease
    BEFORE INSERT OR UPDATE ON invitation_delivery_jobs
    FOR EACH ROW EXECUTE FUNCTION require_worker_job_lease();
CREATE TRIGGER retention_warning_requires_worker_lease
    BEFORE INSERT OR UPDATE ON retention_warnings
    FOR EACH ROW EXECUTE FUNCTION require_worker_job_lease();
CREATE TRIGGER deletion_ledger_requires_worker_lease
    BEFORE INSERT OR UPDATE ON deletion_ledger
    FOR EACH ROW EXECUTE FUNCTION require_worker_purge_lease();

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'findworks_worker') THEN
        CREATE ROLE findworks_worker NOLOGIN NOBYPASSRLS;
    END IF;
END
$$;

GRANT findworks_application TO findworks_worker;
GRANT SELECT, UPDATE ON pi_worker_slots TO findworks_worker;
GRANT UPDATE (lease_owner, heartbeat_at, lease_until, updated_at)
    ON shaping_runtime_work TO findworks_worker;
GRANT UPDATE (heartbeat_at) ON interview_runtime_runs TO findworks_worker;
GRANT UPDATE (heartbeat_at) ON invitation_delivery_jobs TO findworks_worker;
GRANT UPDATE (heartbeat_at) ON retention_warnings TO findworks_worker;
GRANT UPDATE (heartbeat_at) ON deletion_ledger TO findworks_worker;
GRANT DELETE ON invitations, interview_sessions, discovery_participants, audit_records
    TO findworks_worker;
