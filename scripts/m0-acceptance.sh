#!/bin/sh
set -eu
umask 077

mode=${1:-}
[ -n "$mode" ] || { echo "usage: $0 scripted|open-live|verify-live|finalise [options]" >&2; exit 64; }
shift

environment=
release_digest=
output_directory=
scripted_run_id=
restore_drill_id=
run_id=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --environment) environment=$2; shift 2 ;;
    --release-digest) release_digest=$2; shift 2 ;;
    --output-dir) output_directory=$2; shift 2 ;;
    --scripted-run-id) scripted_run_id=$2; shift 2 ;;
    --restore-drill-id) restore_drill_id=$2; shift 2 ;;
    --run-id) run_id=$2; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 64 ;;
  esac
done

config_directory=${M0_ACCEPTANCE_CONFIG_DIR:-}
: "${config_directory:?Set M0_ACCEPTANCE_CONFIG_DIR to protected content-free manifests}"
: "${M0_ACCEPTANCE_COMMAND:?Set packaged acceptance command adapter}"
matrix=docs/m0-traceability.csv

digest() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print "sha256:" $1}'
  else
    shasum -a 256 "$1" | awk '{print "sha256:" $1}'
  fi
}

validate_matrix() {
  awk -F, '
    BEGIN { known=" LIVE-01 LIVE-INTERVIEW-RUBRIC LIVE-FINDINGS-RUBRIC S01-SHAPING S02-ADAPTIVE-INTERVIEW B01-UNKNOWN B02-DECLINE B03-OWNER B04-CONTRADICTION B05-REVISION B06-ASSUMPTION B07-INVITATION B08-OUT-OF-SCOPE F01-INTERRUPTION F02-MODEL F03-PI-DEATH F04-EXTRACTION I01-SCOPE I02-COMPLETION O01-OPERATIONS R01-RESTORE " }
    NR == 1 { next }
    $1 !~ /^[0-9]+$/ || $1 < 1 || $1 > 90 || seen[$1]++ || index(known, " " $4 " ") == 0 || $7 != "pass" { exit 1 }
    END { if (NR != 91) exit 1; for (i=1; i<=90; i++) if (!seen[i]) exit 1 }
  ' "$matrix"
}

validate_checks() {
  awk -F, '
    BEGIN { required=" S01-SHAPING S02-ADAPTIVE-INTERVIEW B01-UNKNOWN B02-DECLINE B03-OWNER B04-CONTRADICTION B05-REVISION B06-ASSUMPTION B07-INVITATION B08-OUT-OF-SCOPE F01-INTERRUPTION F02-MODEL F03-PI-DEATH F04-EXTRACTION I01-SCOPE I02-COMPLETION O01-OPERATIONS R01-RESTORE " }
    NF != 3 || index(required, " " $1 " ") == 0 || seen[$1]++ || $2 != "passed" || $3 !~ /^[A-Za-z0-9._:\/\-]+$/ { exit 1 }
    END { if (NR != 18) exit 1 }
  ' "$1"
}

validate_matrix || { echo "traceability outcome=failed" >&2; exit 1; }
traceability_digest=$(digest "$matrix")

case "$mode" in
  scripted)
    [ "$environment" = staging ] || { echo "scripted acceptance requires staging" >&2; exit 64; }
    case "$release_digest" in sha256:????????????????????????????????????????????????????????????????) ;;
      *) echo "release digest must be SHA-256" >&2; exit 64 ;;
    esac
    : "${output_directory:?Set --output-dir}"
    : "${FINDWORKS_IMAGE:?Set frozen image reference}"
    : "${M0_SKILL_DIGEST:?Set frozen skill digest}"
    : "${M0_EXTENSION_DIGEST:?Set frozen extension digest}"
    case "$FINDWORKS_IMAGE" in *@"$release_digest") ;; *) echo "image/release mismatch" >&2; exit 1 ;; esac
    [ -z "$(git status --porcelain)" ] || { echo "dirty worktree" >&2; exit 1; }
    M0_RELEASE_DIGEST=$release_digest
    M0_GIT_COMMIT=$(git rev-parse HEAD)
    M0_TRACEABILITY_DIGEST=$traceability_digest
    export M0_RELEASE_DIGEST M0_GIT_COMMIT M0_TRACEABILITY_DIGEST
    [ -f "$config_directory/release.json" ] || { echo "release manifest missing" >&2; exit 1; }
    mkdir -m 700 "$output_directory"
    mvn -B clean verify
    RUN_PI_TESTS=true mvn -B -Dtest='*RealPi*Test' test
    : "${M0_PACKAGED_JOURNEY_COMMAND:?Set staging packaged-journey adapter}"
    "$M0_PACKAGED_JOURNEY_COMMAND" "$release_digest" "$output_directory"
    scripts/deploy-smoke.sh "${COMPOSE_FILE:-deploy/compose.yaml}"
    validate_checks "$output_directory/checks.csv" || { echo "scripted checks outcome=failed" >&2; exit 1; }
    results_digest=$(digest "$output_directory/checks.csv")
    "$M0_ACCEPTANCE_COMMAND" record-scripted "$config_directory/release.json" \
      "$output_directory/checks.csv" "$traceability_digest" "$results_digest"
    echo "m0_scripted outcome=passed"
    ;;
  open-live)
    [ "$environment" = pilot-production ] || { echo "live acceptance requires pilot-production" >&2; exit 64; }
    : "${scripted_run_id:?Set --scripted-run-id}"
    : "${restore_drill_id:?Set --restore-drill-id}"
    "$M0_ACCEPTANCE_COMMAND" open-live "$scripted_run_id" "$restore_drill_id"
    ;;
  verify-live)
    : "${run_id:?Set --run-id}"
    [ -f "$config_directory/live-binding.json" ] || { echo "human rubric binding missing" >&2; exit 1; }
    "$M0_ACCEPTANCE_COMMAND" verify-live "$run_id" "$config_directory/live-binding.json"
    ;;
  finalise)
    : "${run_id:?Set --run-id}"
    "$M0_ACCEPTANCE_COMMAND" finalise "$run_id"
    ;;
  *) echo "unknown acceptance mode" >&2; exit 64 ;;
esac
