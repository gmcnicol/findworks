#!/usr/bin/env bash
set -euo pipefail

labels=(
  "wayfinder:map|Canonical Wayfinder map"
  "wayfinder:research|Wayfinder AFK research decision ticket"
  "wayfinder:prototype|Wayfinder HITL prototype decision ticket"
  "wayfinder:grilling|Wayfinder HITL grilling decision ticket"
  "wayfinder:task|Wayfinder prerequisite task"
)

for entry in "${labels[@]}"; do
  name="${entry%%|*}"
  description="${entry#*|}"
  gh label create "$name" --description "$description" --force >/dev/null
  printf 'ready: %s\n' "$name"
done
