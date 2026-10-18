# FindWorks

FindWorks helps people **find what they need to know from other people**.

The first use case is requirements and domain discovery: an Investigator shapes an Interview Mission in their project-aware agent harness, hands it to an external subject-matter expert through FindWorks, and receives structured findings with exact Evidence provenance.

## Current status

`main` remains the canonical discovery baseline described in [`CONTEXT.md`](./CONTEXT.md).
This working tree implements the local M0 journey from issues 47 through 61:

- Java 25
- Spring Boot 4.1 WebMVC
- PostgreSQL + Flyway
- OAuth-protected Streamable HTTP MCP for Codex and Pi
- Mission review, external Interviews, Evidence-backed Findings, retrieval, and recovery
- FindWorks v1 preserves product content; retention-driven and requested deletion are disabled
- Isolated Pi runtime containers and production web/worker topology
- Integration, Playwright, fault-injection, and container-isolation checks

See [`docs/acceptance/m0-local-2026-08-31.md`](./docs/acceptance/m0-local-2026-08-31.md) for local evidence and the external deployment and real-pilot gates that remain.

## Run the pilot shell

One-command development stack:

```bash
docker compose up --build
```

Local JVM development:

```bash
bash scripts/dev-up.sh
```

Local development keeps the deterministic seeded sign-in so automated checks do not depend on an external identity provider. Seeded pilot accounts use password `findworks`:

- `gareth@example.com` — active verified Investigator in `pilot-org`
- `inactive.user@example.com` — denied inactive User
- `inactive.membership@example.com` — denied inactive Membership
- `outsider@example.com` — denied other Organisation

## Clerk authentication

Production authentication uses Clerk email verification codes for **both researchers and interviewees**. One account can do both. `/work` shows saved Discoveries/Missions and assigned interviews; sign-out, browser closure, or changing devices does not lose progress or require another invitation.

Configure the web process with:

```bash
FINDWORKS_CLERK_ENABLED=true
CLERK_PUBLISHABLE_KEY=pk_...
CLERK_SECRET_KEY=sk_...
CLERK_FRONTEND_API=https://your-instance.clerk.accounts.dev
CLERK_ISSUER=https://your-instance.clerk.accounts.dev
CLERK_JWKS_URI=https://your-instance.clerk.accounts.dev/.well-known/jwks.json
CLERK_AUTHORIZED_PARTIES=https://findworks.example
```

On first access, FindWorks registers the verified Clerk identity and may link an existing active Investigator by server-verified primary email, preserving local ownership IDs. Signup never grants research membership. Interviewees claim an invitation with its currently verified recipient email, creating durable Session-specific account access. Research memberships and interview assignments are independent permissions.

See [`docs/clerk-and-continuity.md`](./docs/clerk-and-continuity.md) for email-code instance setup, pilot provisioning, both-role continuity, revocation rules, and live Clerk acceptance.

## Run checks

Focused JVM checks:

```bash
bash scripts/test.sh
```

Browser checks only:

```bash
# Choose a free port; the test runner creates its own disposable Compose stack.
FINDWORKS_E2E_PORT=8081 npm test
```

## Canonical product sources

- [`CONTEXT.md`](./CONTEXT.md) — durable product, domain, and architecture decisions
- [Issue #46](https://github.com/gmcnicol/findworks/issues/46) — implementation handoff specification
- [Issue #47](https://github.com/gmcnicol/findworks/issues/47) — pilot shell implementation slice
- [`docs/research/`](./docs/research) — primary-source research
- [`prototypes/pi-rpc-boundary/`](./prototypes/pi-rpc-boundary) — retained throwaway evidence for the Pi runtime boundary
- [`AGENTS.md`](./AGENTS.md) and [`docs/agents/`](./docs/agents) — repository-specific agent guidance

## Historical implementation

PR [#36](https://github.com/gmcnicol/findworks/pull/36) implemented an earlier M0 based on FindWorks-hosted Investigator shaping. That boundary was later rejected in favour of project-aware harness shaping and semantic MCP submission/retrieval.

The implementation was deliberately removed from `main`. Git history preserves it as negative evidence; it is not a scaffold for this implementation.
