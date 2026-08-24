# M0 operations runbook

This runbook operates the application through its private health/metrics boundary and one-shot `support` role. Never use a database console, ad hoc SQL, environment dump, raw Pi output, provider payload, prompt, Question, answer, quotation, email address, token, model payload, or tool arguments while diagnosing. Do not copy confidential content into tickets or chat.

## Alerts

Each alert has one durable firing row and one resolution transition per finite kind and opaque resource ID. Check private readiness and metrics, the named workload's safe state, and the deployment topology. Do not inspect application content.

| Alert | Safe checks | Recovery and escalation |
|---|---|---|
| `web_outage` | External HTTPS probe, private web readiness, container state | Restart the immutable web image after database readiness. Escalate host, ingress, or TLS failure. |
| `worker_outage` | External worker heartbeat and private readiness | Restart worker. Expired leases are reclaimed. Escalate repeated loss. |
| `database_outage` | External PostgreSQL TLS probe and both readiness endpoints | Keep web/worker fail closed. Restore provider connectivity, then confirm readiness. |
| `old_job` | `status`, safe job kind, age over five minutes | Use `retry-work` only for eligible failed invitation or findings work. Restart worker for queued work. |
| `runtime_failure` | Runtime run state and finite safe error | Retry eligible findings work. Preserve Evidence. Escalate repeated OCI/model failure. |
| `email_failure` | Delivery job state and finite safe error | Correct provider availability, then retry the same job/idempotency identity. |
| `disk_pressure` | External host disk percentage and container temporary storage | Free only reviewed operational artefacts. Never remove PostgreSQL or Evidence files manually. |
| `backup_failed`, `backup_stale` | Authoritative #34 backup feed and `verify-backup` | Follow the provider recovery runbook. Local timestamps are not backup proof. |
| `purge_overdue` | `verify-deletion` ledger stage and deadline | Restore purge worker, verify completion, then escalate any invariant failure. |
| `telemetry_gap` | External scrape/export heartbeat and receiver availability | Restore the selected sink/receiver. Domain commits continue without telemetry. Verify recovery transition. |

Alert delivery failure never rolls back Evidence or other domain commits. Web/database/host death and total telemetry loss require an independently hosted external probe and receiver.

## Support commands

Run the digest-pinned application image with the `support` profile, narrow support database role, tenant identity, named `SUPPORT_OPERATOR_ID`, finite `reason-code`, and opaque target ID. Output is content-free except the deliberately approved `view-break-glass` terminal result. Each successful command writes the stated audit event in the same transaction.

| Command | Preconditions | Audit event | Retry or rollback |
|---|---|---|---|
| `status` | `reason-code=incident_response` | `support_status_inspected` | Read-only; repeat safely. |
| `retry-work` | Failed `invitation_email` or `findings_extraction` only | `support_work_retried` | Preserves delivery identity/history. Do not retry shaping/interview turns manually. |
| `extend-retention` | Active Discovery; new ISO instant later than current due date | `support_retention_extended` | Forward-only extension; no shortening rollback. |
| `revoke-access` | Existing Interview Session | `support_access_revoked` | Idempotent revocation; issue a normal replacement invitation if authorised. |
| `terminate-session` | Active or paused Session | `support_session_terminated` | Irreversible lifecycle transition; accepted Evidence remains. |
| `verify-deletion` | Existing ledger ID | `support_deletion_verified` | Read-only; absence is not proof of deletion. |
| `verify-backup` | Authoritative #34 backup ID | `support_backup_verified` | Read-only; `unavailable` means no live proof. |

Set `SUPPORT_COMMAND`, `SUPPORT_KIND`, `SUPPORT_ID`, `SUPPORT_UNTIL`, and `SUPPORT_REASON_CODE` for the one-shot Compose service. Never pass a free-form break-glass reason on the command line or in an environment variable. Mount it as the `findworks.operations.reason` config-tree secret, run `request-break-glass` with a maximum four-hour expiry, then destroy that temporary secret file.

## Break glass

1. The named operator requests exact Evidence scope using `incident_diagnosis`, `data_recovery`, or `security_investigation`, a protected free-form reason file, and an expiry no more than four hours away. Audit: `break_glass_requested`.
2. The owning Investigator reviews the reason in the authenticated approval page and approves or rejects. Audit: `break_glass_approved` or `break_glass_rejected`.
3. Only the same operator may run `view-break-glass` before expiry. The content is written only to that terminal. Audit stores only request identity: `break_glass_content_viewed`.
4. Run `revoke-break-glass` immediately after use. Audit: `break_glass_revoked`. Expiry and revocation fail closed.

Never redirect the content command to telemetry, shared files, shell history, tickets, or chat. A rejected, expired, wrong-operator, wrong-resource, or revoked request cannot be overridden. Escalate through the Investigator, not a database edit.
