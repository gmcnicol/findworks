#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true
cleanup() { docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM
docker compose up --build -d
until curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; do
  sleep 1
done
# Keep the Playwright-owned server process alive and stream only application logs.
docker compose logs --follow app
