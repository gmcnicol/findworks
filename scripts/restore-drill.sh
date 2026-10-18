#!/usr/bin/env bash
set -euo pipefail
: "${FINDWORKS_RESTORE_DATABASE_URL:?Use an isolated restored database URL}"
: "${FINDWORKS_RESTORE_DATABASE_USERNAME:?Set the read-only restore role}"
: "${FINDWORKS_RESTORE_DATABASE_PASSWORD:?Set the restore password}"
export PGPASSWORD="$FINDWORKS_RESTORE_DATABASE_PASSWORD"
psql "$FINDWORKS_RESTORE_DATABASE_URL" -U "$FINDWORKS_RESTORE_DATABASE_USERNAME" -v ON_ERROR_STOP=1 <<'SQL'
select case when exists(select 1 from evidence e left join questions q on q.id=e.question_id where e.question_id is not null and q.id is null) then 1/0 else 1 end as evidence_provenance_ok;
select count(*) as inert_legacy_deletion_ledger_rows from deletion_ledger;
select count(*) as retained_audit_events from audit_events;
SQL
unset PGPASSWORD
