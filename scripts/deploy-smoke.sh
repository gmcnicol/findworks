#!/bin/sh
set -eu

compose_file=${1:-deploy/compose.yaml}
container_cli=${CONTAINER_CLI:-podman}
: "${PUBLIC_ORIGIN:?Set canonical public HTTPS origin}"

case "$PUBLIC_ORIGIN" in https://*) ;; *) echo "PUBLIC_ORIGIN must use HTTPS" >&2; exit 64 ;; esac
curl --fail --silent --show-error "$PUBLIC_ORIGIN/login" >/dev/null

published=$("$container_cli" compose -f "$compose_file" ps --format '{{.Service}} {{.Publishers}}')
printf '%s\n' "$published" | grep '^ingress ' >/dev/null
if printf '%s\n' "$published" | grep -E '^(web|worker|migrate|support|egress) .*[0-9]+:' >/dev/null; then
  echo "A private service has a published host port" >&2
  exit 1
fi

"$container_cli" compose -f "$compose_file" exec -T web \
  wget --no-verbose --tries=1 --spider http://localhost:8082/actuator/health/readiness
"$container_cli" compose -f "$compose_file" exec -T worker \
  wget --no-verbose --tries=1 --spider http://localhost:8082/actuator/health/readiness
