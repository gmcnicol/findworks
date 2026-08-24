#!/bin/sh
set -eu
umask 077

: "${RECOVERY_PROVIDER_COMMAND:?Set provider restore adapter executable}"
: "${RECOVERY_COMMAND:?Set application recovery adapter executable}"
: "${RECOVERY_MANIFEST_FILE:?Set signed synthetic or approved fixture manifest}"
: "${RECOVERY_POINT:?Set requested recovery timestamp}"

incident_at=${RECOVERY_INCIDENT_AUTHORISED_AT:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}
work_directory=$(mktemp -d "${TMPDIR:-/tmp}/findworks-restore.XXXXXX")
candidate_handle="$work_directory/candidate.handle"
ledger_handle="$work_directory/ledger.handle"
policy="$work_directory/policy.json"
candidate_metadata="$work_directory/candidate.json"
ledger_metadata="$work_directory/ledger.json"
bundle="$work_directory/deletion-ledger.bundle"

cleanup() {
  "$RECOVERY_PROVIDER_COMMAND" destroy "$candidate_handle" >/dev/null 2>&1 || true
  "$RECOVERY_PROVIDER_COMMAND" destroy "$ledger_handle" >/dev/null 2>&1 || true
  rm -f "$candidate_handle" "$ledger_handle" "$policy" "$candidate_metadata" \
    "$ledger_metadata" "$bundle"
  rmdir "$work_directory" 2>/dev/null || true
}
trap cleanup EXIT HUP INT TERM

"$RECOVERY_PROVIDER_COMMAND" policy "$policy"
"$RECOVERY_PROVIDER_COMMAND" restore candidate "$RECOVERY_POINT" "$candidate_handle"
"$RECOVERY_PROVIDER_COMMAND" restore ledger latest "$ledger_handle"
"$RECOVERY_PROVIDER_COMMAND" metadata "$candidate_handle" "$candidate_metadata"
"$RECOVERY_PROVIDER_COMMAND" metadata "$ledger_handle" "$ledger_metadata"

"$RECOVERY_COMMAND" migrate "$candidate_handle"
"$RECOVERY_COMMAND" migrate "$ledger_handle"
"$RECOVERY_COMMAND" export-ledger "$ledger_handle" "$ledger_metadata" "$bundle"
"$RECOVERY_COMMAND" apply "$candidate_handle" "$candidate_metadata" "$ledger_metadata" \
  "$policy" "$bundle" "$RECOVERY_MANIFEST_FILE" "$incident_at"

echo "restore_drill outcome=verified"
