# Agent Working Agreement

Read [`CONTEXT.md`](./CONTEXT.md) before making product or architecture decisions.

## FindWorks is in discovery

Do not start implementing the product merely because an implementation path appears obvious. The current goal is to use Wayfinder to remove product and architecture ambiguity, then hand a decision-complete result to `to-spec` and `to-tickets`.

Distinguish clearly between:

- facts already established in `CONTEXT.md`;
- working architectural direction;
- decisions still requiring Wayfinder investigation;
- implementation detail that should wait until the route is clear.

Do not silently promote a working assumption into a product decision.

## Matt Pocock engineering skills

This repo is intended to use Matt Pocock's engineering skills under Pi. Before using Wayfinder or downstream engineering skills, run `/skill:setup-matt-pocock-skills` once so this repository's issue tracker and domain-document locations are configured under `docs/agents/`.

Use GitHub Issues as the issue tracker unless the human explicitly chooses another tracker during setup.

For Wayfinder:

- Invoke it explicitly with `/skill:wayfinder` in Pi.
- The destination is a decision-complete FindWorks M0 specification, not implemented software.
- Decision tickets resolve uncertainty; they are not disguised implementation tickets.
- Respect HITL ticket boundaries. A grilling session must ask the human rather than answer for them.
- Before resolving a Wayfinder issue, inspect its GitHub labels as well as its body. Treat `wayfinder:research`, `wayfinder:prototype`, `wayfinder:grilling`, and `wayfinder:task` as workflow gates, not decorative categories.
- Prefer the product's own vocabulary: Discovery, Interview Mission, Interview Session, Investigation Item, Knowledge Item, Evidence.
- Keep the first vertical slice narrow enough to prove the investigator -> mission -> interviewee -> findings loop.

## Architecture guardrails

- Keep FindWorks business/domain concepts out of any shared SaaS kernel.
- Do not introduce generic entity/attribute storage for the FindWorks domain.
- Prefer a conventional relational model and modular-monolith boundaries until evidence demands otherwise.
- Treat Pi as an agent runtime boundary, not as the authoritative store for FindWorks product state.
- Do not leak raw model/Pi event formats into the stakeholder-facing web contract; use stable semantic application events/models.
- Avoid premature microservice or distributed-system decomposition.

## Documentation

Keep durable product/domain decisions in `CONTEXT.md` or linked ADR/spec artefacts as the project matures. Wayfinder's map and decision tickets remain the canonical record of the decision route while the map is active.

## Agent skills

### Issue tracker

Issues live in this repository's GitHub Issues. See `docs/agents/issue-tracker.md`.

### Triage labels

Default triage label vocabulary is used. See `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repository. See `docs/agents/domain.md`.
