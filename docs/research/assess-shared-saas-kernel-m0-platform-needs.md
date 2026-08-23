# Shared SaaS kernel fit for FindWorks M0 platform needs

Research date: 22 August 2026

## Conclusion

Do not make the shared kernel a dependency of FindWorks M0.

The project is now an implemented **semantic workflow kernel**, not a reusable identity, tenancy, invitation, email, privacy, or general audit subsystem. It provides useful product-neutral mechanics for tenant-contained semantic evaluation, Cedar-authorised Action Offers, durable Intents and Events, immutable execution evidence, and operational telemetry. Those mechanics do not satisfy most of FindWorks' settled access and data-lifecycle contract.

FindWorks M0 would still need to own Investigator accounts, verified email, organisations and memberships, Discovery ownership, magic-link invitations, email delivery, interviewee browser sessions, product-wide audit, retention warnings, deletion, backup expiry, TLS, encryption at rest, cookies, and rate limiting. Adopting the kernel would add Java 25, Spring Boot 4.1, PostgreSQL, Taxi-generated bindings, Cedar, Kernel migrations, and the Action Offer and Intent execution model without removing that work.

The smallest safe route is a standalone FindWorks modular monolith with an application-specific relational schema. Reuse the kernel's tested ideas where they fit, especially transaction-local tenant context, forced RLS, least-privilege database roles, fail-closed authorisation, content-free telemetry, and forward-only migrations. Reconsider the library only if FindWorks later needs its semantic Action, Intent, Event, and temporal evaluation pipeline for product behaviour, independently of the M0 platform concerns assessed here.

## Sources and scope

This assessment compares FindWorks' settled M0 contract in [`CONTEXT.md`](../../CONTEXT.md) with the shared kernel's current remote `main` at commit [`ed5d1f5`](https://github.com/gmcnicol/saas-kernel/tree/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7). It uses the kernel's source code, migrations, tests, and first-party documentation. The older sibling checkout was not used as evidence because it was behind remote `main`.

## Capability assessment

| FindWorks M0 need | Kernel evidence | Fit |
| --- | --- | --- |
| Investigator identity with verified email | The public `Principal` is only a validated `type` and `id`; authentication is outside the Kernel API. The CRM fixture uses Spring Security HTTP Basic and a single configured username and role, not accounts or email verification. [Principal](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/src/main/java/io/github/gmcnicol/kernel/application/Principal.java#L3-L9), [fixture security](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/applications/keep-in-touch-crm/src/main/java/io/github/gmcnicol/crm/WebSecurityConfiguration.java#L15-L22), [fixture user](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/applications/keep-in-touch-crm/src/main/resources/application.properties#L1-L4) | Missing. FindWorks must own identity and verified-email state or integrate an identity provider. |
| Organisation membership | The kernel accepts an opaque tenant ID, validates its syntax, and installs it as transaction-local PostgreSQL context. It has no organisation or membership model and does not prove that a supplied principal belongs to a tenant. [tenant context](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/src/main/java/io/github/gmcnicol/kernel/internal/TenantContext.java#L7-L31), [Kernel API](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/src/main/java/io/github/gmcnicol/kernel/application/Kernel.java#L9-L38) | Partial containment primitive only. FindWorks must authenticate membership and derive trusted organisation context before any database or Kernel call. |
| Cross-organisation isolation | Kernel tables use forced PostgreSQL RLS keyed by transaction-local tenant context, and the shipped roles are non-superuser and `NOBYPASSRLS`. [database roles](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/src/main/resources/db/kernel/migration/V1__initialise_kernel.sql#L2-L24), [Intent and Event RLS](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/src/main/resources/db/kernel/migration/V4__execute_typed_actions.sql#L105-L133) | Useful pattern. It protects only Kernel-owned tables. FindWorks must add equivalent policies and tests to its own domain tables. |
| Discovery-owner authorisation | Cedar filters typed projection fields, Facts, and semantic Actions for a supplied principal. Its model is deliberately about Action Offers and invocation, not arbitrary application CRUD or Discovery ownership. [security contract](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/docs/security.md#L1-L11), [Cedar adapter](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/src/main/java/io/github/gmcnicol/kernel/internal/CedarAuthoriser.java#L60-L93) | Not a direct fit. M0 still needs application and database rules for organisation and Discovery ownership. Using Cedar for this narrow pilot would be optional extra machinery. |
| Email invitation and single-use magic link | No production source defines invitations, token exchange, recipient binding, email delivery, or resumable interviewee browser sessions. The Kernel artefact dependencies contain no web security or mail subsystem; applications own delivery. [Kernel dependencies](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/pom.xml#L26-L114), [application ownership boundary](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/docs/persistence.md#L3-L14) | Missing. Implement as FindWorks invitation and session records plus an email adapter. Keep raw tokens out of storage and logs. |
| Product-wide audit | The kernel stores and queries ordered status transitions for semantic Intents. `IntentAuditEntry` contains Intent lifecycle fields, not the actor, organisation resource, and outcome vocabulary required for authentication, Mission approval, invitations, Interview Sessions, findings, access denial, retention, and deletion. [Intent audit shape](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/src/main/java/io/github/gmcnicol/kernel/application/IntentAuditEntry.java#L7-L16), [audit query](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/src/main/java/io/github/gmcnicol/kernel/internal/IntentQueryService.java#L59-L88), [audit table](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/src/main/resources/db/kernel/migration/V4__execute_typed_actions.sql#L61-L73) | Narrow reusable mechanism, wrong contract. FindWorks needs its own content-free audit records. Do not stretch Intent audit into product audit. |
| Retention, warning, extension, and deletion | Kernel documentation assigns retention and physical PostgreSQL operation to each application. Its stored workflow evidence is immutable and there is no automatic TTL, archival, compaction, or public deletion API. [operator ownership](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/docs/operators.md#L41-L43), [persistence boundary](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/docs/persistence.md#L10-L23), [public API](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/src/main/java/io/github/gmcnicol/kernel/application/Kernel.java#L9-L38) | Missing. Worse, using Kernel evidence for deletable interview content would require a supported purge contract across its foreign-key graph. Avoid that coupling in M0. |
| Security controls | Strong available pieces are fail-closed typed input limits, Cedar denial on errors, forced tenant RLS, least-privilege roles, checksummed evidence, and HMAC-derived subject correlation in structured logs. [security contract](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/docs/security.md#L1-L11), [telemetry correlation](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/src/main/java/io/github/gmcnicol/kernel/internal/KernelTelemetry.java#L120-L176), [HMAC](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/kernel/src/main/java/io/github/gmcnicol/kernel/internal/KernelTelemetry.java#L231-L239) | Partial. TLS, encryption at rest, hashed invitation tokens, secure browser cookies, application rate limits, email security, and content-safe application logging remain FindWorks or deployment responsibilities. |

## Required adapters if the kernel is adopted

Adoption would require all of the following outside the kernel:

1. An identity adapter that authenticates the Investigator, verifies email, resolves exactly one pilot organisation membership, and produces a trusted `Principal` and tenant ID.
2. FindWorks-owned organisation, membership, Discovery, Interview Mission, invitation, Interview Session, Investigation Item, Knowledge Item, Evidence, findings-review, retention, and audit tables with forward-only migrations.
3. A magic-link service that creates high-entropy tokens, stores only token hashes, binds recipient and approved Interview Mission version, expires and revokes links, exchanges once for a secure browser session, and supports reissue.
4. An email delivery adapter and delivery lifecycle record.
5. An authorisation adapter that enforces organisation and Discovery ownership for ordinary application reads and writes. The Kernel's Cedar path could separately govern semantic Action Offers if those are used.
6. Product-wide content-free audit independent of `typed_intent_audit`.
7. Retention jobs, 14-day warnings, manual extension, immediate access revocation, seven-day content purge, 12-month audit tombstone deletion, and deployment-specific 30-day backup ageing.
8. A supported purge adapter for any FindWorks content copied into Kernel Projected State, Candidate Payload, Event, or Intent evidence. No such API exists today.

This is effectively the whole M0 platform layer. The kernel removes none of these adapters.

## Coupling risks

### Wrong reuse boundary

The library's public surface is semantic evaluation, authorisation, presentation, Action Offers, Intents, Events, and reevaluation. FindWorks' immediate platform need is identity, invitation, constrained participant access, audit, and lifecycle deletion. Depending on the library for tenant IDs or RLS alone couples M0 to a much larger execution model.

### Technology lock-in before product proof

Current kernel consumption requires Java 25, Spring Boot 4.1, PostgreSQL, Taxi compilation and generated bindings, and Cedar Java. [reactor baseline](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/pom.xml#L7-L19), [version set](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/pom.xml#L51-L60), [getting started](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/docs/getting-started.md#L1-L20). The repository POM is still `0.1.0-SNAPSHOT`, so M0 would also depend on an unreleased development contract. [project version](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/pom.xml#L14-L19)

### Deletion conflict

The kernel intentionally retains immutable workflow evidence and exposes no delete operation. FindWorks promises selective Interview Session deletion and whole-Discovery purging. Copying interview text or derived findings into Kernel evidence would create two authoritative retention surfaces and an unsupported coordinated purge problem.

### Duplicate provenance models

FindWorks Evidence means exact human source material supporting a Knowledge Item. Kernel evidence means canonical encoded Projected State, Facts, Candidate Payloads, Events, and execution provenance. [kernel persistence boundary](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/docs/persistence.md#L10-L14) Reusing the name or storage would blur two distinct concepts. Keep FindWorks Evidence entirely application-owned.

### Licence assumption is stale

FindWorks currently describes the separate project as MIT-licensed. Current kernel source and Maven metadata use Apache License 2.0. [licence](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/LICENSE#L1-L4), [POM licence](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/pom.xml#L22-L28) This does not block reuse, but the specification must correct the factual assumption and comply with Apache 2.0 if code is copied or the artefact is distributed.

## Domain boundary

The kernel can keep FindWorks concepts outside itself if it is used exactly as designed. Its persistence guide requires application-specific relational tables and explicitly prohibits a generic entity, attribute, relationship, value, or JSONB domain store. [persistence boundary](https://github.com/gmcnicol/saas-kernel/blob/ed5d1f5a1e04a60c26c6c2408df7013ee22686e7/docs/persistence.md#L3-L23) FindWorks concepts would live in its application schema and, only where genuinely useful, application-owned semantic definitions and adapters.

That architectural boundary is sound. It is not enough reason to adopt the library for M0. If future FindWorks behaviour maps naturally to the kernel, integration should depend only on the public `Kernel` interface, pass stable application-owned IDs, store no raw Pi or model event shapes, and avoid placing Discovery, Interview Mission, Interview Session, Investigation Item, Knowledge Item, or Evidence logic inside the shared project.

## Decision input

For M0, choose **standalone** and treat the shared kernel as a source of proven infrastructure patterns, not a dependency.

Reconsider adoption only after both conditions hold:

- FindWorks has a concrete need for the kernel's semantic Action Offer and durable Intent/Event pipeline, rather than only identity, tenancy, invitations, or audit; and
- the deletion contract has a supported way to purge every Kernel record derived from a deleted Discovery or Interview Session without weakening other applications' immutable-evidence guarantees.
