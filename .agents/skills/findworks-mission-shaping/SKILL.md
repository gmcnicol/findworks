---
name: findworks-mission-shaping
description: Shape and submit a FindWorks Interview Mission from project context when an Investigator wants to learn something from a subject-matter expert. Use for creating or revising a Mission, not for conducting the interview or inventing the interviewee's answers.
---

# FindWorks Mission Shaping

Turn the Investigator's discovery objective into one complete, reviewable Interview Mission. FindWorks receives only the deliberately shared snapshot. Project context remains shaping context, never Interview Evidence.

Read [references/mission-contract.md](references/mission-contract.md) before drafting or submitting.

## Ground the Mission

1. Read repository instructions first.
2. List tracked files with `git ls-files`. Inspect only material relevant to the objective: domain documents, specifications, issues, source, and tests.
3. Exclude credentials, secrets, environment files, private keys, generated data, ignored files, and unrelated material. An explicit named exception may extend relevant project context, but never permits credential or secret access.
4. Treat every project claim as fallible shaping context. Turn stale, uncertain, or contradictory claims into Investigation Items instead of answering for the interviewee.

Grounding is complete when every inspected source is relevant, safe, and classified as shared or private.

## Shape Adaptively

Ask one focused question at a time. Follow ambiguity, assumptions, conflicting language, and ownership boundaries rather than walking through fields in order.

- Use `sdlc_decide` for a bounded choice when available. Include a recommendation and a custom-answer route. If unavailable, ask the same single question in prose.
- Use free text for open discovery.
- Identify the intended interviewee by role and relevance only. Recipient name and email remain FindWorks web decisions.
- Distinguish Investigator statements from agent proposals. Confirm every material proposal before marking it `CONFIRMED_AGENT_PROPOSAL`.
- Keep Unknown, Conflict, Ownership Gap, and supported Knowledge as valid outcomes. Required means an explicit outcome is required, not a preferred answer.
- Keep opening questions as non-binding tone and direction guidance.

After each answer, update a structured draft under the path returned by:

```bash
git rev-parse --git-path findworks/mission-drafts
```

Store fields, open questions, confirmations, source locators, and shared/private classifications. Store no transcript, secret, credential, recipient contact detail, or copied project-source content. The Git metadata directory is private local state and cannot be committed.

Shaping is complete only when every contract field is explicit and every ambiguity is resolved or deliberately represented by an Investigation Item.

## Confirm and Submit

Present one final checkpoint containing:

- exact shared MCP payload;
- new versus existing Discovery target;
- source locators without source content;
- a content-free summary of withheld private context;
- statement that submission creates a draft only and cannot approve or invite.

Ask for explicit confirmation of the exact payload and all four contract confirmations. Do not submit before confirmation.

Use `create_discovery_with_draft_mission` for a new Discovery or `add_draft_mission` for an owned existing Discovery. Generate one submission identity and retain it with the exact payload until the result is unambiguous.

- Structured validation failure: keep the draft, map every JSON-Pointer violation to its field, and resume shaping at the first unresolved problem.
- Transport ambiguity: retry the exact payload with the same submission identity.
- Submission conflict: keep the draft and explain that the identity was already consumed by different content.
- Created or replayed success: show the returned review link and identities, then delete only that local draft.

Completion means FindWorks returned `created` or `replayed`, the review link is available, and no private draft remains.
