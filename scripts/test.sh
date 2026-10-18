#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

cleanup() {
  docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

source scripts/use-java-25.sh

postgres_port="${FINDWORKS_POSTGRES_PORT:-5432}"
docker compose up -d postgres >/dev/null
until pg_isready -h localhost -p "$postgres_port" -U findworks -d findworks >/dev/null 2>&1; do
  sleep 1
done

export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:${postgres_port}/findworks"
export SPRING_DATASOURCE_USERNAME="findworks"
export SPRING_DATASOURCE_PASSWORD="findworks"

mvn clean test
npm test
