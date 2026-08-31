#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

source scripts/use-java-25.sh

docker compose up -d postgres >/dev/null
trap 'docker compose stop postgres >/dev/null' EXIT

until pg_isready -h localhost -p 5432 -U findworks -d findworks >/dev/null 2>&1; do
  sleep 1
done

export FINDWORKS_DATABASE_URL="jdbc:postgresql://localhost:5432/findworks"
export FINDWORKS_DATABASE_USERNAME="findworks"
export FINDWORKS_DATABASE_PASSWORD="findworks"

mvn spring-boot:run
