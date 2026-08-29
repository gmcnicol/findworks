# FindWorks

FindWorks helps people **find what they need to know from other people**.

The first use case is requirements and domain discovery: an Investigator shapes an Interview Mission in their project-aware agent harness, hands it to an external subject-matter expert through FindWorks, and receives structured findings with exact Evidence provenance.

## Current status: product discovery

FindWorks is using Wayfinder to reach a decision-complete M0 specification. There is intentionally no product implementation on `main` while product and architecture boundaries remain open.

The canonical sources are:

- [`CONTEXT.md`](./CONTEXT.md) — durable product, domain, and architecture decisions;
- [Wayfinder map #1](https://github.com/gmcnicol/findworks/issues/1) — the active decision route;
- [`docs/research/`](./docs/research) — primary-source research;
- [`prototypes/pi-rpc-boundary/`](./prototypes/pi-rpc-boundary) — retained throwaway evidence for the Pi runtime boundary.

Repository-specific agent guidance lives in [`AGENTS.md`](./AGENTS.md) and [`docs/agents/`](./docs/agents).

## Discovery workflow

1. Complete the open Wayfinder decision route.
2. Confirm the result is decision-complete.
3. Run `to-spec` to create the revised M0 specification.
4. Run `to-tickets` to create one coherent implementation backlog.
5. Implement only from that approved handoff.

Do not infer product behaviour from historical code.

## Historical implementation

PR [#36](https://github.com/gmcnicol/findworks/pull/36) implemented an earlier M0 based on FindWorks-hosted Investigator shaping. That boundary was later rejected in favour of project-aware harness shaping and semantic MCP submission/retrieval.

The implementation was deliberately removed from `main`. Git history preserves it as evidence of the assumptions and coupling the revised specification must avoid; it is not a scaffold for the next implementation.

## Wayfinder setup

This repository uses GitHub Issues and Matt Pocock's engineering skills under Pi. The repository setup is recorded under [`docs/agents/`](./docs/agents). If labels need restoring, run:

```bash
bash scripts/bootstrap-wayfinder-labels.sh
```

Then invoke Wayfinder explicitly in Pi:

```text
/skill:wayfinder
```
