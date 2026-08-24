#!/bin/sh
set -eu

role="${1:-}"
case "$role" in
  local)
    exec java -jar /app/app.jar --findworks.process-role=local
    ;;
  web|worker)
    exec java -jar /app/app.jar --findworks.process-role="$role" --spring.flyway.enabled=false
    ;;
  migrate)
    exec java -jar /app/app.jar --findworks.process-role=migrate \
      --spring.main.web-application-type=none --spring.flyway.enabled=true
    ;;
  *)
    echo "usage: entrypoint local|web|worker|migrate" >&2
    exit 64
    ;;
esac
