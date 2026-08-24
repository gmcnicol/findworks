#!/bin/sh
set -eu

[ "$#" -eq 2 ] || { echo "usage: $0 staging.env production.env" >&2; exit 64; }
for key in FINDWORKS_DATABASE_ID FINDWORKS_PROVIDER_ACCOUNT_ID PUBLIC_ORIGIN DATABASE_URL; do
  left=$(sed -n "s/^${key}=//p" "$1")
  right=$(sed -n "s/^${key}=//p" "$2")
  [ -n "$left" ] && [ -n "$right" ] || { echo "$key is missing" >&2; exit 1; }
  [ "$left" != "$right" ] || { echo "$key overlaps between environments" >&2; exit 1; }
done
grep '^FINDWORKS_ENVIRONMENT=staging$' "$1" >/dev/null
grep '^FINDWORKS_ENVIRONMENT=production$' "$2" >/dev/null
