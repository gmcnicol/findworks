# M0 deployment runbook

This repository supplies one-host topology and gates. It does not prove a live host, TLS, managed PostgreSQL, provider delivery, or network isolation.

## Prepare

1. Build the JAR with `mvn -q clean verify` and record its SHA-256.
2. Build `Dockerfile` with a digest-pinned `JAVA_RUNTIME_IMAGE`. Build `runtime/pi/Dockerfile` with a digest-pinned `PI_BASE_IMAGE`. Push both once and use only their repository digests.
3. Run rootless Podman as a dedicated worker identity. Give only the worker process its socket. Create the named internal Pi network and attach only the reviewed HTTPS allow-list proxy.
4. Supply distinct staging and production configuration, database roles, domains, provider accounts, key IDs, and secret files. Staging contains synthetic records only. Run `scripts/check-environment-isolation.sh staging.env production.env`.
5. Configure the host firewall so only TCP 443 reaches ingress. Deny direct web, worker, PostgreSQL, actuator, debugger, rootless socket, and Pi ports. Verify direct IP, IPv6, DNS, private, link-local, metadata, daemon, database, web, and cross-turn Pi paths on the real Linux host.

## Role and secret matrix

The same application image runs `web`, `worker`, `migrate`, one-shot `support`, one-shot `recovery`, or one-shot `acceptance`. Web, worker, support, recovery, and acceptance disable Flyway. Migrate enables Flyway, serves no HTTP, and exits after validation/migration.

- Ingress: TLS certificate and key only.
- Web: web database password, Investigator password, invitation token keys.
- Worker: worker database password, invitation token keys, model credential, checkpoint key, dedicated rootless OCI socket.
- Migrator: schema-owner database password only.
- Support: narrow support database password, named operator ID, break-glass key only when requested, and a protected reason supplied as a config-tree file. It receives no schema-owner, worker, model, email-provider, or runtime credential.
- Recovery: short-lived recovery database password, signed-bundle key, opaque restore metadata, and private bundle directory only. It receives no web, email, model, OCI, or schema-owner credential. Use [backup-restore-runbook.md](backup-restore-runbook.md).
- Acceptance: support database password, named operator identity, and one protected content-free input manifest. It receives no web, email, model, OCI, recovery, break-glass, or schema-owner credential.
- Pi turn: model credential and one turn-scoped FindWorks credential through stdin. No host environment, mount, database credential, socket, or application secrets.

Secret files are mounted at runtime and imported with Spring `configtree`. Rotate a file, revoke the old value, and restart only its consumer. Never put values in environment files, Compose literals, image layers, command arguments, logs, checkpoints, Mission context, or browsers.

## Migrate then switch

Verify the managed backup and the immediately previous build against a production-like database copy. Set `APPROVE_PRODUCTION_SWITCH=yes`, then run `scripts/deploy.sh`. It validates Compose, runs the exact new image as the one-shot migrator, switches worker and web only after migration succeeds, starts ingress, and runs synthetic health and exposure checks. Never use Flyway clean, a down migration, or schema reset.

Rollback uses the previous image only if its compatibility check passed against the advanced schema. Otherwise keep traffic on the last healthy compatible process and fix forward.

## Degraded behaviour

Web readiness depends on PostgreSQL, not worker availability. A stopped worker leaves durable jobs queued. Worker restart reclaims expired owner-checked leases; two database slots cap Pi work while invitation and retention schedulers continue separately. A failed readiness check keeps ingress from a new web process. Health and metrics bind to the private management port and `monitor_private` network; ingress rejects `/actuator`. External probes and alert routing must remain outside this host failure boundary. One host remains one availability boundary.

See [operations-runbook.md](operations-runbook.md) for content-free alerts, audited support commands, and break-glass approval.
