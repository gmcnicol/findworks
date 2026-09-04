#!/usr/bin/env bash
set -euo pipefail
: "${FINDWORKS_RESTORE_DATABASE_URL:?Use an isolated restored database URL}"
: "${FINDWORKS_RESTORE_DATABASE_USERNAME:?Set the read-only restore role}"
: "${FINDWORKS_RESTORE_DATABASE_PASSWORD:?Set the restore password}"
export PGPASSWORD="$FINDWORKS_RESTORE_DATABASE_PASSWORD"
psql "$FINDWORKS_RESTORE_DATABASE_URL" -U "$FINDWORKS_RESTORE_DATABASE_USERNAME" -v ON_ERROR_STOP=1 <<'SQL'
select case when exists(select 1 from evidence e left join questions q on q.id=e.question_id where e.question_id is not null and q.id is null) then 1/0 else 1 end as evidence_provenance_ok;
select case when exists(select 1 from discoveries d join deletion_ledger l on l.kind='DISCOVERY' and l.resource_id=d.id and l.purged_at is null where d.state<>'DELETING') then 1/0 else 1 end as deletion_denial_reapplied;
select case when exists(select 1 from deletion_ledger where purge_due_at>requested_at+interval '7 days' or backup_expiry_at>requested_at+interval '30 days') then 1/0 else 1 end as deletion_bounds_ok;
select case when exists(select 1 from audit_events where expires_at>created_at+interval '12 months 1 minute') then 1/0 else 1 end as tombstone_bound_ok;
SQL
unset PGPASSWORD
