# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

The M0 primary user is an Investigator: initially a software engineer conducting requirements or domain discovery. They shape Interview Missions in a project-aware agent harness, then use FindWorks to review, approve, administer, and follow up that work.

An external Interviewee uses a separate low-friction web experience without creating an account.

## Product Purpose

FindWorks helps people find what they need to know from other people. It turns a deliberate knowledge-seeking objective into an adaptive Interview Mission, retains answers as Evidence, and returns structured findings with provenance.

M0 succeeds when an Investigator can move coherently from submitted draft Mission through approval, invitation, Interview Session, findings review, and reviewed-context retrieval without losing uncertainty or attribution.

## Positioning

FindWorks is not a fixed questionnaire or “AI Typeform.” Its Interview Missions describe intent and coverage while a skill-driven agent asks answer-dependent follow-ups, records honest unresolved outcomes, and connects each useful claim to exact Evidence.

## Operating Context

- Mission shaping starts in the Investigator’s project-aware harness and enters FindWorks through MCP.
- The Investigator uses the authenticated FindWorks workspace to review and approve exact Mission versions, invite an Interviewee, monitor progress, and review findings.
- The Interviewee receives a focused, account-free interview experience.
- Reviewed Discovery context can return to an authorised Investigator harness through MCP.

## Capabilities and Constraints

- The Investigator workspace requires functional global navigation for Dashboard, Discoveries, Missions, findings requiring review, and harness access. It must not contain dead placeholder destinations.
- Discovery, Interview Mission, Interview Session, Investigation Item, Knowledge Item, and Evidence are the canonical product terms.
- The owning Investigator may access only active records they own within their organisation.
- Mission lifecycle, invitation lifecycle, Interview Session progress, findings extraction, and findings review are distinct states.
- FindWorks uses a conventional relational model in a modular monolith. Pi is an isolated runtime boundary, not the authoritative product store.
- Stable semantic application models must hide raw Pi/model event formats from the web experience.
- M0 supports one pilot organisation, one Investigator, one Interviewee per Mission journey, and text answers only.

## Evidence on Hand

- `CONTEXT.md` is the durable product, domain, UX, and architecture record.
- Issue #46 is the M0 implementation handoff specification.
- `docs/acceptance/m0-local-2026-08-31.md` records local acceptance evidence and remaining external gates.
- Existing server-rendered Investigator and Interviewee routes provide runnable workflow evidence; they do not yet constitute a coherent Investigator workspace.
- No testimonials, customer logos, usage benchmarks, or production claims are available and none should be fabricated.

## Product Principles

- Keep one primary task clear at each stage while preserving orientation across the wider Discovery lifecycle.
- Show structured knowledge and honest uncertainty rather than hiding gaps behind conversational output.
- Make provenance easy to inspect without making transcripts the primary knowledge model.
- Keep stakeholder UX calm and simple; keep administrative and technical detail Investigator-facing.
- Never invent an Interviewee’s answer or silently broaden an approved Interview Mission.

## Accessibility & Inclusion

The web experience must remain keyboard-operable, responsive, semantically structured, and legible with visible focus states. The external Interviewee flow must preserve low-friction access without requiring an account.
