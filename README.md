# FindWorks

**Find what you need.**

FindWorks is an early-stage product for structured human discovery: an engineer, analyst, product person, or other investigator defines what they need to learn; an agent harness helps shape that into an interview mission; a stakeholder or SME is interviewed through a simple web experience; and structured findings return with provenance, unknowns, conflicts, and follow-up work.

This repository is intentionally at the discovery stage. The first job is to find the route to a coherent M0 before implementation starts.

## Core loop

1. Investigator describes the discovery objective.
2. The harness grills the investigator until the interview mission is clear.
3. The investigator approves and hands the mission to an interviewee.
4. The interviewee completes an adaptive, skill-driven interview.
5. The harness returns structured findings, evidence, unknowns, conflicts, and suggested follow-ups.
6. Further interviews or downstream artefacts can be created from that knowledge.

The product is not intended to be an AI form builder. The interview should adapt to answers and seek missing knowledge rather than march through a fixed questionnaire.

## Wayfinder bootstrap

FindWorks uses Matt Pocock's engineering skills to discover the product before building it.

For Codex, Pi, or another Agent Skills-compatible harness, from a local clone run:

```bash
npx skills@latest add mattpocock/skills
```

Install at least:

- `setup-matt-pocock-skills`
- `wayfinder`
- `grilling`
- `domain-modeling`
- `research`
- `prototype`
- `to-spec`
- `to-tickets`

Then run, once for this repository:

```text
/setup-matt-pocock-skills
```

Use GitHub Issues as the issue tracker and keep the repository's root `CONTEXT.md` as the primary domain context.

After setup, start the product discovery with:

```text
/wayfinder
```

A good initial destination is:

> Reach a decision-complete specification for FindWorks M0: enough product, domain, UX, runtime, persistence, and integration decisions are settled that `/to-spec` and then `/to-tickets` can hand implementation to agents without inventing product decisions.

Wayfinder is for finding the route, not implementing the product. Keep implementation out of the map unless explicitly required to unblock a decision.

## Current state

See [`CONTEXT.md`](./CONTEXT.md) for durable context and open architectural questions.
