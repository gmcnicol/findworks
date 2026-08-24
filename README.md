# FindWorks

**Find what you need.**

FindWorks is an early-stage product for structured human discovery: an engineer, analyst, product person, or other investigator defines what they need to learn; an agent harness helps shape that into an interview mission; a stakeholder or SME is interviewed through a simple web experience; and structured findings return with provenance, unknowns, conflicts, and follow-up work.

This repository now contains the first production foundation for M0: a Java 25 Spring Boot modular monolith, PostgreSQL schema migrations, operational health checks, and a server-rendered web entry point.

## Core loop

1. Investigator describes the discovery objective.
2. The harness grills the investigator until the interview mission is clear.
3. The investigator approves and hands the mission to an interviewee.
4. The interviewee completes an adaptive, skill-driven interview.
5. The harness returns structured findings, evidence, unknowns, conflicts, and suggested follow-ups.
6. Further interviews or downstream artefacts can be created from that knowledge.

The product is not intended to be an AI form builder. The interview should adapt to answers and seek missing knowledge rather than march through a fixed questionnaire.

## Wayfinder bootstrap with Pi

FindWorks uses Matt Pocock's engineering skills to discover the product before building it.

From a local clone:

```bash
gh auth status
npx skills@latest add mattpocock/skills
```

Install the skills for Pi and include at least:

- `setup-matt-pocock-skills`
- `wayfinder`
- `grilling`
- `domain-modeling`
- `research`
- `prototype`
- `to-spec`
- `to-tickets`

Start Pi from the repository root:

```bash
pi
```

Trust the project if Pi asks you to. Project-local skills are only loaded for trusted projects. If the skills were installed while Pi was already running, use `/reload`.

Run the one-time repository setup:

```text
/skill:setup-matt-pocock-skills
```

Use GitHub Issues as the issue tracker and keep the repository's root `CONTEXT.md` as the primary domain context. Let the setup skill create the detailed repository-specific configuration under `docs/agents/`.

### Fresh-repo label bootstrap

Wayfinder creates GitHub issues with `wayfinder:*` labels, so make sure those labels exist before the first map is charted:

```bash
bash scripts/bootstrap-wayfinder-labels.sh
```

The script is idempotent and creates/updates:

- `wayfinder:map`
- `wayfinder:research`
- `wayfinder:prototype`
- `wayfinder:grilling`
- `wayfinder:task`

Then start product discovery:

```text
/skill:wayfinder
```

A good initial destination is:

> Reach a decision-complete specification for FindWorks M0: enough product, domain, UX, runtime, persistence, and integration decisions are settled that `to-spec` and then `to-tickets` can hand implementation to agents without inventing product decisions.

Wayfinder is for finding the route, not implementing the product. Keep implementation out of the map unless explicitly required to unblock a decision.

## Current state

See [`CONTEXT.md`](./CONTEXT.md) for durable context and open architectural questions.

## Run locally

Requirements: Java 25, Maven 3.9+, Docker, and an authenticated Pi installation for Discovery shaping.

```bash
mvn verify
docker compose up --build --wait
```

Open [http://localhost:8080](http://localhost:8080). Create a Mission, approve it, open its private
invitation link in another browser, answer the interview, then review the Evidence-backed findings.

Sign in locally as `investigator@findworks.local` with password `findworks`. Override both through
`INVESTIGATOR_EMAIL` and `INVESTIGATOR_PASSWORD`; `.env.example` lists the available settings.

For local Java development, start only PostgreSQL and run Spring Boot directly:

```bash
docker compose up -d --wait postgres
mvn spring-boot:run
```

Interview Pi turns are denied by default. To build their runtime image, use a verified digest-pinned
Node base image and the repository root as the build context:

```bash
podman build -f runtime/pi/Dockerfile \
  --build-arg PI_BASE_IMAGE=node:24-alpine@sha256:<verified-digest> \
  -t findworks/pi .
```

Enable `INTERVIEW_RUNTIME_ENABLED` only after setting a digest-pinned `PI_RUNTIME_IMAGE`, an explicit
provider credential, a 32-byte Base64 AES checkpoint key and key ID, and verifying the configured OCI
engine reports rootless mode. The default `PI_RUNTIME_NETWORK=none` deliberately blocks live model
access. A networked deployment also requires a credential-free HTTPS egress proxy and independent
allow-list enforcement.

Health endpoints:

- `GET /actuator/health/liveness`
- `GET /actuator/health/readiness`

Run checks and package the application:

```bash
mvn verify
```
