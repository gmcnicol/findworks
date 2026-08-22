# FindWorks Context

## Product proposition

FindWorks helps people **find what they need to know** from other people.

The first use case is requirements and domain discovery: an investigator (for example an engineer, analyst, product person, or architect) needs knowledge held by one or more SMEs or stakeholders. FindWorks helps shape the investigation, hand it to an interviewee, conduct an adaptive interview, and return structured findings with provenance.

The product must stay broader than a requirements questionnaire. The underlying abstraction is a human-facing investigative skill harness that can later support requirements discovery, handovers, process capture, policy discovery, domain modelling, incident debriefs, and similar knowledge-seeking work.

## Core product loop

1. Create a discovery objective.
2. Grill the investigator to clarify what must be learned and from whom.
3. Produce an **Interview Mission**: the deliberate package of knowledge-seeking work handed to one interviewee.
4. Investigator reviews and approves the mission.
5. Interviewee receives a simple web session, ideally via a low-friction invite or magic link.
6. A skill-driven agent conducts an adaptive interview rather than replaying a fixed form.
7. Answers are retained as evidence while useful knowledge is extracted into structured findings.
8. Findings return to the investigator as confirmed knowledge, assumptions, conflicts, unknowns, ownership/delegation, and suggested follow-ups.
9. Further Interview Missions or downstream artefacts can be generated from the resulting knowledge.

## Established product principles

- **Not AI Typeform.** Questions adapt to what the interviewee says.
- **One human speaks for themselves.** The agent must not invent the SME's side of a HITL exchange.
- **Ask the right owner.** If the interviewee identifies another owner, record/delegate that gap instead of forcing them to answer outside their expertise.
- **Provenance matters.** Structured findings must trace back to the source answer/interviewee/evidence.
- **Transcript is evidence, not the knowledge model.** Downstream work should consume structured knowledge rather than repeatedly re-read giant conversations.
- **Coverage over question count.** The investigator approves the mission's intent and coverage, not a brittle fixed questionnaire.
- **Keep stakeholder UX simple.** The interviewee should see a calm, focused question-and-answer experience, not engineering or agent internals.
- **Expose uncertainty.** Unknowns, assumptions, disagreements, and unresolved ownership are first-class outputs.

## Important domain concepts

### Discovery

A longer-lived investigation that can span multiple people, missions, sessions, and outputs.

### Interview Mission

The explicit handover contract between investigator and interviewee. It captures the objective, relevant known context, areas to investigate, boundaries, terminology, and completion criteria. It must not blindly expose the investigator's entire conversation to the interviewee agent.

### Interview Session

A runtime interaction with one participant under one Interview Mission.

### Investigation Item

A question, unknown, decision, or knowledge gap that needs resolution. Questions are emitted to resolve investigation items; the question itself is not the primary domain object.

### Knowledge Item

Structured knowledge derived from evidence. Likely categories include fact, rule, decision, assumption, term, exception, conflict, and unknown.

### Evidence

The answer, attachment, or other source supporting a knowledge item.

## Agent/runtime direction already discussed

The preferred direction is to use **Pi (or an equivalent agent harness) as the agent runtime**, rather than have the web application concatenate `SKILL.md` files into direct OpenAI API requests.

The intended boundary is:

- FindWorks owns product/domain state, participants, missions, knowledge, provenance, persistence, permissions, and UX.
- Pi owns the agent loop, skill loading, model/provider interaction, and tool execution.
- OpenAI can be a model provider behind Pi; it is not the FindWorks orchestration layer.
- A small custom Pi extension is likely to expose FindWorks-specific tools such as `ask_question`, `record_fact`, `record_assumption`, `record_conflict`, `record_unknown`, `create_investigation`, `delegate_investigation`, and `complete_interview`.
- FindWorks should expose a stable semantic protocol to its web UI rather than leaking raw Pi event shapes to the browser.

Treat the exact Pi integration mechanism (RPC process, SDK service, or another shape) as a decision still to be proven.

## Architecture direction, not yet all decisions

A likely high-level shape is:

```text
Web UI
  -> FindWorks application/domain
       -> persistent relational store
       -> agent-runtime adapter
            -> Pi + FindWorks extension + skills
                 -> model provider
```

A conventional relational domain model is preferred over a generic entity/attribute system. A lightweight relationship table can express knowledge/investigation dependencies without introducing a graph database prematurely.

A modular monolith is the expected starting point unless Wayfinder surfaces evidence that another deployment shape is required.

## SaaS kernel

There is an existing separate MIT-licensed SaaS kernel project that may provide reusable platform concerns such as identity, tenancy, membership, invitations, permissions, audit, notifications, files, and common event infrastructure.

**Whether FindWorks should depend on that kernel is not yet a settled FindWorks decision.** If adopted, the kernel must remain ignorant of FindWorks concepts such as Pi, skills, interviews, discoveries, and knowledge items. FindWorks must keep a normal application-specific domain/schema rather than encoding the product into generic kernel tables.

## M0 destination

The immediate goal is not to build the whole platform. Use Wayfinder to reach a **decision-complete specification for an M0 vertical slice**.

The candidate slice is:

```text
investigator creates discovery
  -> harness grills investigator
  -> investigator approves Interview Mission
  -> interviewee receives invitation
  -> adaptive Pi/skill interview runs
  -> structured findings return to investigator
```

The Wayfinder effort is complete when enough product, domain, UX, runtime, persistence, and integration decisions are settled that `/to-spec` and `/to-tickets` can proceed without implementation agents inventing product decisions.

## Keep out of the initial map unless it becomes necessary

- General-purpose workflow/platform ambitions beyond the M0 loop.
- Large integration catalogues.
- Premature microservice decomposition.
- Generic low-code/data-model abstractions.
- Full reporting/analytics productisation.
- Multi-channel stakeholder clients beyond the first web experience.

## Open questions suitable for Wayfinder

These are deliberately not pre-decided; Wayfinder should sharpen and order them rather than assume answers:

- What exactly must the M0 prove to count as successful?
- What does the investigator-side UX need before handover?
- What is the minimal Interview Mission contract?
- How does the interview agent decide its next question and completion?
- Which knowledge categories are truly required in M0?
- What needs to be synchronous versus persisted/streamed?
- How should Pi be hosted and isolated per interview/session?
- What FindWorks tools should the Pi extension expose?
- Where is the authoritative state boundary between Pi and FindWorks?
- What is the minimum auth/invitation model for an external interviewee?
- Should M0 consume the shared SaaS kernel or remain standalone initially?
- What evidence/provenance guarantees are required from day one?
- What is deliberately deferred until after the first end-to-end proof?
