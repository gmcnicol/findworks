#!/bin/sh
set -eu

compose_file=${COMPOSE_FILE:-deploy/compose.yaml}
container_cli=${CONTAINER_CLI:-podman}
: "${FINDWORKS_IMAGE:?Set immutable application image digest}"
: "${APPROVE_PRODUCTION_SWITCH:?Set to yes after database backup and approval}"

case "$FINDWORKS_IMAGE" in
  *@sha256:????????????????????????????????????????????????????????????????) ;;
  *) echo "FINDWORKS_IMAGE must be pinned by SHA-256 digest" >&2; exit 64 ;;
esac
[ "$APPROVE_PRODUCTION_SWITCH" = yes ] || { echo "Production switch not approved" >&2; exit 64; }

"$container_cli" compose -f "$compose_file" config --quiet
"$container_cli" compose -f "$compose_file" --profile migrate run --rm migrate
"$container_cli" compose -f "$compose_file" up -d --no-build --wait egress
"$container_cli" compose -f "$compose_file" up -d --no-build --wait worker web
"$container_cli" compose -f "$compose_file" up -d --no-build --wait ingress
scripts/deploy-smoke.sh "$compose_file"
printf '%s\n' "$FINDWORKS_IMAGE"
