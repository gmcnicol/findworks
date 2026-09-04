#!/usr/bin/env bash
set -euo pipefail

node --test runtime/auth.test.mjs

image=findworks-runtime:isolation-test
first=findworks-runtime-isolation-a-$$
second=findworks-runtime-isolation-b-$$
cleanup() { docker rm -f "$first" "$second" >/dev/null 2>&1 || true; }
trap cleanup EXIT INT TERM

docker build -q -f runtime/Dockerfile -t "$image" runtime >/dev/null
for spec in "$first:a" "$second:b"; do
  container="${spec%:*}"
  secret="${spec##*:}"
  docker run -d --name "$container" --read-only --network none \
    --tmpfs /tmp:rw,noexec,nosuid,size=1m --cap-drop ALL \
    --security-opt no-new-privileges --pids-limit 64 --memory 512m --cpus .5 \
    --user 10001:10001 --entrypoint sh "$image" \
    -c "printf '%s' '$secret' >/tmp/secret; sleep 30" >/dev/null
done

test "$(docker exec "$first" cat /tmp/secret)" = a
test "$(docker exec "$second" cat /tmp/secret)" = b
test "$(docker inspect -f '{{.HostConfig.ReadonlyRootfs}}' "$first")" = true
test "$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$first")" = none
test "$(docker inspect -f '{{.HostConfig.PidsLimit}}' "$first")" = 64
test "$(docker inspect -f '{{len .Mounts}}' "$first")" = 0
! docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' "$first" | grep -Eq 'DATABASE|PASSWORD|TOKEN|OPENAI_API_KEY'
