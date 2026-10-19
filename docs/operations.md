# FindWorks M0 operations

## Topology

One immutable application build runs as `web` and private `worker` processes. Caddy is the only public service. Managed PostgreSQL, the configured transactional email endpoint, and the model provider are the only external application dependencies. PostgreSQL holds durable work and leases. There is no broker, cache, object store, or service split.

The worker talks to a rootless Docker daemon. Every Pi turn gets a fresh read-only container, private 32 MB temporary filesystem, non-root user, dropped capabilities, no-new-privileges, 64-process limit, 512 MB memory, half a CPU, 120-second wall limit, and destruction after settlement. The internal Docker network has no direct internet route. Squid permits only the pinned model-provider domain. Runtime containers receive one five-minute turn capability and a model credential, never database, email, operator, web-session, or other runtime credentials.

## Environments and secrets

Use separate domains, databases, provider projects, keys, and rootless Docker daemons for local, staging, and production. Staging contains synthetic data only. Supply secrets through the host secret store into process environment at start. Do not put values in images, Compose files, logs, checkpoints, browser code, or Mission content. Rotate one process-scoped credential at a time and restart only its consumer.

Use separate least-privilege PostgreSQL roles. The web role owns migrations and interactive data operations. The worker role leases and completes invitation email, runtime, and extraction work. The restore role can read an isolated restored database only.

## Release and rollback

Build and scan three immutable images: `Dockerfile`, `Dockerfile.worker`, and `runtime/Dockerfile`. Pin image digests in the production environment. Run local verification, inspect the forward-only Flyway migration, deploy to staging, run Playwright and fault tests, then request manual production approval. Never roll a database migration back. Roll the application back only to a version compatible with the migrated schema.

Production command, for the human operator only:

```bash
docker compose --env-file deploy/production.env -f compose.production.yml up -d --no-build
```

## Health, outages, and telemetry

`/health` reports process health without content. `/operator/status` reports content-free counts for pending and failed invitation email, running, deferred, and failed runtime work, model-runtime circuit state, failed extraction, and `deletion_enabled: false`. Alert on unavailable health, database connection failure, email exhaustion, runtime exhaustion, extraction failure, and missing telemetry.

- Database outage: web mutations fail and no answer is acknowledged. Existing pages fail closed. Workers retain no alternative state.
- Model or runtime outage: accepted Evidence remains committed. The worker owns one three-attempt budget (Pi's nested retry loop is disabled), waits 2–4 seconds and then 8–10 seconds with stable jitter, and ends in the participant recovery screen after exhaustion. Three consecutive dependency failures open the shared model-runtime circuit for 30 seconds; one half-open probe then either resets or reopens it. The participant working page refreshes into the next question or recovery state without requiring a manual reload.
- Email outage: Invitation remains pending or failed and is never labelled delivered.
- Telemetry outage: application work continues, while a separate host check raises a telemetry-gap alert.
- Proxy or web outage: no internal service is exposed directly.

Logs contain timestamp, service, severity, action code, outcome, correlation identity, and opaque resource identity only. Never log request bodies, answer text, Evidence, email addresses, tokens, model events, tool parameters, or arbitrary JSON.

## Data preservation, backup, and restore

Deletion is disabled for v1. Do not run retention selection, retention warnings, deletion requests, retention extensions, deletion-ledger denial reapplication, or physical purge. The corresponding HTTP routes fail closed, and `scripts/findworks-operator` does not expose them. Legacy retention columns, deletion markers, warning rows, and deletion-ledger rows remain inert under the forward-only schema. Do not manually change, restore, or purge them as part of this rollout.

Configure encrypted daily managed snapshots and point-in-time recovery of five minutes or better under the approved infrastructure backup policy. Before restored traffic is enabled, run `scripts/restore-drill.sh` against the isolated restore network. Restore validation proves committed Evidence and provenance remain intact; it does not reapply legacy deletion state or execute purge work.

Access revocation, invitation revocation/reissue, Interview Session termination, and ordinary content-free audit remain active operational controls because they do not delete product content.
