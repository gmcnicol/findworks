# M0 acceptance runbook

This repository supplies a fail-closed acceptance harness. It does not substitute synthetic actors for the genuine Investigator, external domain expert, or human semantic and accessibility judgement.

## Freeze and scripted proof

Freeze one digest-pinned application image, Git commit, Flyway checksum digest, Pi runtime image digest, skill digest, extension digest, provider alias, model alias, and `docs/m0-traceability.csv` digest in the protected `release.json`. Do not include prompts, Questions, answers, quotations, emails, tokens, notes, model payloads, or tool arguments.

Set `M0_PACKAGED_JOURNEY_COMMAND` to the reviewed staging adapter. It must start the exact packaged web, worker, isolated OCI, and fake-or-test email boundary; drive the named synthetic scenarios through public HTTP and worker leases; and write exactly 18 content-free `check_id,passed,evidence_locator` rows to `checks.csv`. It must not claim production email, model, isolation, TLS, telemetry, or recovery proof. `scripts/m0-acceptance.sh scripted` also runs the existing deterministic and opt-in real-Pi suites and the external deployment smoke check.

Set `M0_ACCEPTANCE_COMMAND` to a thin wrapper around the immutable image's `acceptance` entrypoint. For `record-scripted`, the wrapper receives the release manifest, checks CSV, validated traceability matrix, traceability digest, and results digest. It records each check's evidence locator and all 90 story-to-criterion/check/locator links in the protected JSON input. For the other commands it creates the corresponding protected input, mounts it read-only, and passes no database-owner, web, email, model, OCI, recovery, or break-glass credential. It never executes SQL.

## Genuine pilot

Run `open-live` only after the scripted run and current provider restore drill pass for the exact release and schema. The command creates only an opaque acceptance run. It must not create the Discovery, prefill an answer, approve a Mission, send an Invitation, confirm completion, review a Knowledge Item, or accept a Findings Package.

One verified software engineer Investigator and a different external domain expert then complete a genuine bounded software-change journey in separate browsers. Use ordinary confidential information only. Exclude regulated data, credentials, production secrets, and unnecessary personal data. Keep the release and runtime configuration frozen.

The Investigator and observer complete [m0-human-checklist.md](m0-human-checklist.md). Store only its booleans, opaque IDs, and the live story evidence locators from the frozen traceability matrix in protected `live-binding.json`; retained Mission, Evidence, findings, citations, and decision notes remain authoritative application records. Missing or mismatched live locators fail AC35-8. Scripted acceptance never supplies them.

Run `verify-live`, then `finalise`. The verifier checks exact binding, informed start, confirmed completion, required outcomes, immutable Evidence, current-version review, accepted package and notes, exact citation offsets, scope, idempotent effects, current scripted evidence, all 90 traceability rows, and current restore evidence. Any failure creates immutable failed criterion evidence. Fix the product and open a new run. Never edit a failed run or the database directly.

## Live blockers

Completion requires the two authorised humans, genuine subject, pilot-production HTTPS, real transactional email, rootless OCI isolation and egress enforcement, model account, managed PostgreSQL, monitoring and alert receiver, support operator, provider backup policy, and a current authorised two-restore drill. Repository and synthetic results cannot prove these.
