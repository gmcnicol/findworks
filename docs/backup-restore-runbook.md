# Backup and restore runbook

This repository supplies a provider-neutral two-restore gate. It does not prove managed PostgreSQL encryption, PITR, snapshot policy, retention, recovery-point spacing, or a four-hour live recovery. Record those only from the selected provider and an authorised isolated drill.

## Provider and recovery adapters

`scripts/restore-drill.sh` calls two reviewed executables. Neither may print credentials or protected content.

The provider adapter accepts:

- `policy OUTPUT`: write content-free encryption, PostgreSQL version, PITR gap, daily snapshot, retention, region, and key-ID evidence.
- `restore candidate TIMESTAMP HANDLE`: restore the requested point into a new private instance with web, worker, email, model, and public traffic disabled.
- `restore ledger latest HANDLE`: restore the latest recoverable deletion ledger into a second private instance.
- `metadata HANDLE OUTPUT`: write opaque restore ID, backup ID, requested/achieved point, and PostgreSQL timeline.
- `destroy HANDLE`: delete the isolated restore and revoke its short-lived credentials.

The recovery adapter accepts `migrate`, `export-ledger`, and `apply`. It injects each handle's credential into the same digest-pinned FindWorks image through secret files. It runs only `migrate` or `recovery` roles. It never accepts SQL. `apply` verifies signed metadata and manifest, imports the latest ledger idempotently, blocks restored targets, replays due/completed deletion through the normal retention seam, verifies the graph, and records one append-only content-free result.

PostgreSQL 17 synthetic drills use a custom `pg_dump` archive, an empty database created from `template0`, pre-created fixed application roles, and `pg_restore --single-transaction --no-owner`. Restoring ACLs is required for the least-privilege recovery role. `--single-transaction` implies exit on error. Synthetic dumps test procedure only. They do not prove provider PITR or encryption.

## Authorised drill

1. Authorise recovery and record start time. Use a synthetic staging dataset for routine drills. Production-shaped drills require explicit approval.
2. Confirm provider policy evidence: encryption at rest and in transit, usable recovery-point gap at most five minutes, snapshots at least daily, every backup/clone retained at most 30 days, isolated restore support, and available encryption key.
3. Create or select a signed fixture manifest containing only opaque IDs and SHA-256 digests. Never include Evidence, quotations, emails, tokens, prompts, model payloads, or tool arguments.
4. Set `RECOVERY_POINT`, adapter paths, and manifest path. Run `scripts/restore-drill.sh`. Candidate and latest-ledger restores remain private.
5. The recovery gate stays closed until ledger replay and all checks pass. A failed, missing, truncated, tampered, wrong-timeline, wrong-tenant, stale, or over-four-hour run records a finite failure and never enables web or worker.
6. Review the content-free result: requested and achieved restore point, timeline, ledger high-water mark/count, replay count, Evidence/provenance check counts, access/retention/deletion booleans, image digest, schema version, duration, and provider policy.
7. An authorised operator may switch ingress only after a `provider` result reaches `ready` for the exact image and schema. A synthetic result is never traffic-ready and never reports backup verified to operations.
8. Destroy both restores, revoke credentials, remove bundles, and list provider backups/restores to prove no drill artefact can outlive 30 days.

Never restore over production, run `flyway clean`, use down migrations, open public traffic before verification, use database consoles, perform ad hoc SQL repair, dump environments, or copy protected content into logs, tickets, or chat. Keep the current primary untouched. Failed candidates remain private and are destroyed.

Repeat before pilot and after material schema, PostgreSQL major version, provider, backup policy, encryption key, topology, or deletion-graph changes. Exact application-image gating is deliberately stricter: create a current provider drill record before enabling a new production image.

Live deletion durability remains bounded by the provider's measured recovery-point gap. A zero-loss deletion requirement needs an independently durable ledger store and is outside M0.
