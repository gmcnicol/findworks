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
  support)
    exec java -jar /app/app.jar --findworks.process-role=support \
      --spring.main.web-application-type=none --spring.flyway.enabled=false
    ;;
  recovery)
    exec java -jar /app/app.jar --findworks.process-role=recovery \
      --findworks.recovery.run-command=true \
      --spring.main.web-application-type=none --spring.flyway.enabled=false
    ;;
  migrate)
    exec java -jar /app/app.jar --findworks.process-role=migrate \
      --spring.main.web-application-type=none --spring.flyway.enabled=true
    ;;
  *)
    echo "usage: entrypoint local|web|worker|migrate|support|recovery" >&2
    exit 64
    ;;
esac
