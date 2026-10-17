# Periodic investigation review spike

Status: evaluation spike for GitHub issue #67. This is not a production multi-agent architecture decision.

## Question

Does one occasional, separate Evidence/coverage review make the existing conversational interviewer more efficient or reliable enough to justify its extra latency, cost, and operational surface?

The single-interviewer `openai/gpt-5.4-mini` / low-reasoning run remains the baseline. The candidate uses the same model and reasoning effort for both roles so the comparison isolates role separation and cadence rather than a model upgrade.

## Responsibilities

### Interviewer

The interviewer remains solely responsible for the human conversation and the Runtime Turn:

- ask one grounded Question with one information need;
- use interviewee vocabulary and stay inside the approved Mission;
- independently map immutable Evidence to outcome proposals;
- submit exactly one next action through `submit_interview_turn`;
- propose completion only when authoritative required coverage is terminal.

It may inspect validated `reviewer_guidance`, but that guidance is `ADVISORY_ONLY`. It is not Evidence, accepted coverage, a persisted outcome, or permission to complete.

### Reviewer

The reviewer never converses with the interviewee and never submits a Runtime Turn. It occasionally inspects one server-created snapshot and proposes:

- Evidence-linked outcomes that may have been missed, especially Conflicts;
- a next-thread priority;
- whether required coverage would be ready after applying its own advisory outcome proposals.

It may identify insufficiency, but it does not draft the next Question. It cannot mutate Evidence, Mission data, Investigation Results, Session revision, or completion state.

### FindWorks server

The server owns cadence and every authoritative input. It creates the reviewer request, validates the proposal, attaches accepted advice to one interviewer context, and still validates the interviewer's ordinary Runtime Turn. PostgreSQL remains authoritative in a production implementation; this spike's in-process gate models that seam without adding a persistence schema or agent framework.

## Minimal seam

The spike adds one deep module at the server/reviewer seam:

```js
const request = createReviewerRequest({ context, trigger });
const accepted = gate.accept({ request, proposal, context: currentAuthoritativeContext });
// accepted.context is a clone with one ADVISORY_ONLY reviewer_guidance value.
```

The module hides request projection, immutable-Evidence hashing, scope checks, completion checks, idempotent replay, stale-revision rejection, and safe context enrichment. Callers never apply reviewer fields themselves.

### Request contract (`periodic-investigation-review/1.0.0`)

```json
{
  "schemaVersion": 1,
  "requestId": "server-derived identity",
  "sessionId": "server-owned Interview Session identity",
  "missionVersion": 3,
  "expectedRevision": 7,
  "evidenceRevision": "sha256 of the complete authoritative projection",
  "trigger": "INITIAL_EVIDENCE | PERIODIC | UNCERTAINTY | THREAD_BOUNDARY",
  "responsibility": "review-only instruction",
  "mission": { "objective": "approved shared Mission content only" },
  "sharedMissionEntries": [],
  "investigationResults": [],
  "questionsAndEvidence": [],
  "conversationState": {}
}
```

`evidenceRevision` covers the approved shared Mission projection, Investigation Results and current outcomes, ordered Questions and immutable Evidence, and conversation state. Private Investigator context, other Sessions, participant contact details, findings review, and runtime/provider data are absent.

### Response proposal contract

```json
{
  "schemaVersion": 1,
  "requestId": "copied exactly",
  "proposalId": "proposal-<requestId>",
  "sessionId": "copied exactly",
  "missionVersion": 3,
  "expectedRevision": 7,
  "evidenceRevision": "copied exactly",
  "outcomeProposals": [
    {
      "resultId": "in-scope Investigation Result",
      "coverage": "SUPPORTED | UNKNOWN | CONFLICT | ASSUMPTION | OWNERSHIP_GAP",
      "category": "optional Knowledge category",
      "evidenceIds": ["exact supplied Evidence identity"],
      "summary": "bounded advisory summary"
    }
  ],
  "nextThreadProposal": {
    "resultId": "in-scope Investigation Result",
    "reason": "bounded advisory reason",
    "evidenceIds": []
  },
  "completionAssessment": {
    "status": "READY | NOT_READY",
    "openRequiredResultIds": [],
    "reason": "bounded advisory reason",
    "evidenceIds": []
  }
}
```

`nextThreadProposal` may be `null`. Unknown fields are rejected, preventing the reviewer from smuggling a Question, answer rewrite, or mutation command into interviewer context.

## Validation before influence

A proposal cannot enter interviewer context unless all of these hold:

1. request, Session, exact approved Mission version, expected Session revision, and Evidence revision match;
2. every Investigation Result and Evidence identity is in the server-created request;
3. every outcome has supported coverage/category, a bounded summary, and exact provenance;
4. a `READY` assessment has no projected-open required result after applying the proposal's outcome deltas;
5. a `NOT_READY` assessment names exactly all projected-open required results;
6. the proposal contains only the contract's advisory fields;
7. reuse of the same request/proposal identity has byte-equivalent canonical content.

An identical replay returns the same accepted guidance. Identity reuse with different content fails. Stale, invalid, or out-of-scope proposals do not alter context. The interviewer then independently checks accepted advice, and the existing `submit_interview_turn` validation remains the only way a model proposal can affect authoritative outcomes or the next action.

Evidence text is never rewritten. Reviewer proposals contain references and advice, not replacement Evidence. Mission content remains the approved version from the request. The spike introduces no reviewer persistence, cross-session memory, delegation, negotiation, autonomous hand-off, or general multi-agent framework.

## Cadence under test

The candidate does not review every turn. FindWorks asks for review:

- after the first accepted Evidence;
- after an answer explicitly recorded as `DONT_KNOW`, `OWNER_GAP`, or `DECLINED`;
- on the first accepted answer after the active thread identity changes;
- otherwise after four accepted answers since the last accepted review.

The first review establishes a coverage check for short fixtures; uncertainty and thread triggers target high-value boundaries; the four-answer cap tests periodic protection in long sessions. The report records invocations, accepted/rejected proposals, trigger counts, cadence, and reviewer-only latency. A per-turn reviewer is deliberately excluded because the issue asks whether an occasional review earns its overhead.

## Evaluation and decision rule

`npm run evaluate:periodic-review` runs the same versioned synthetic fixtures first through the single interviewer and then through interviewer plus reviewer. Both strategies pin the same commit, image, Pi version, provider, model, reasoning effort, fixture versions, and repeat count. For every successful execution, the evaluator also requires and retains the actual provider, requested model, provider-response model, and configured Pi reasoning identity separately for the baseline interviewer, reviewed interviewer, and reviewer. Missing identity, configured-role substitution, or any cross-role response-model drift invalidates the comparison. Retained traces contain synthetic Evidence only and record role-specific identity, latency, and provider-reported usage/cost when available.

The report compares:

- useful Evidence-linked answers per Question;
- missed expected Conflicts;
- completion errors (premature or fixture-incorrect completion/action);
- deterministic, required-semantic, and continuity failures;
- median cost per Question and p95 end-to-end turn latency;
- reviewer cadence and rejected proposals.

The candidate is a **GO** only if it improves at least one reliability signal, regresses none of the safety/reliability signals, does not reduce useful Evidence per Question, costs no more than 2× per Question, and has no more than 2× p95 latency. Otherwise the decision is **NO_GO**. A GO would justify designing production persistence and recovery later; it would not authorise a general multi-agent framework.
