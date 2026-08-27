# FindWorks Context

## Product proposition

FindWorks helps people **find what they need to know** from other people.

The first use case is requirements and domain discovery: an investigator (for example an engineer, analyst, product person, or architect) needs knowledge held by one or more SMEs or stakeholders. FindWorks helps shape the investigation, hand it to an interviewee, conduct an adaptive interview, and return structured findings with provenance.

The product must stay broader than a requirements questionnaire. The underlying abstraction is a human-facing investigative skill harness that can later support requirements discovery, handovers, process capture, policy discovery, domain modelling, incident debriefs, and similar knowledge-seeking work.

## Core product loop

1. Shape a discovery objective in the Investigator's chosen agent harness.
2. Grill the Investigator there to clarify what must be learned and from whom.
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
- **Structure is FindWorks' job.** The interviewee must not classify Knowledge Items, manage Investigation Items, inspect provenance metadata, or understand agent/runtime concepts. FindWorks derives structure from a natural conversation and exposes technical detail only to the Investigator where needed for review.
- **Expose uncertainty.** Unknowns, assumptions, disagreements, and unresolved ownership are first-class outputs.

## Important domain concepts

### Discovery

A longer-lived investigation that can span multiple people, missions, sessions, and outputs.

### Interview Mission Shaping

An adaptive conversation between an Investigator and their chosen agent harness, grounded in the Investigator's project context, used to create or revise an Interview Mission. A dedicated FindWorks companion skill conducts project-aware grilling, applies the Mission completeness rules, captures minimal source references, and submits the structured draft through the FindWorks MCP boundary rather than cloning the shaping transcript. The MCP contract remains normative for other harness integrations. A submitted proposal becomes authoritative only when the Investigator approves its exact version in FindWorks.

### Discovery Participant

An external person represented only within one Discovery. A Discovery Participant is not an authenticated User and is never silently linked to the same email address in another Discovery.

### Interview Mission

The explicit handover contract between investigator and interviewee. It captures the objective, relevant known context, areas to investigate, boundaries, terminology, and completion criteria. It must not blindly expose the investigator's entire conversation to the interviewee agent.

Every meaningful save creates an immutable full Interview Mission version, including its Investigation Items. Approval freezes one exact version; further editing creates a new draft version and supersedes the previous approval. Investigation Items need no identity across Mission versions in M0.

### Interview Session

A runtime interaction with one participant under one Interview Mission.

### Interview Access Grant

A revocable browser credential created when an Invitation is redeemed. Invitation reissue revokes prior Invitations and Interview Access Grants while preserving the same Interview Session.

### Investigation Item

A question, unknown, decision, or knowledge gap that needs resolution. Questions are emitted to resolve investigation items; the question itself is not the primary domain object.

### Investigation Result

The evolving result of pursuing one Investigation Item in one Interview Session. It moves from unaddressed to exploring to terminal and may contain current Knowledge Items alongside Unknowns, Conflicts, or Ownership Gaps.

### Knowledge Item

One independently reviewable claim derived from Evidence. M0 categories are Fact, Rule, Decision, Term, Exception, and Assumption. Related Knowledge Items may share Evidence and explicit relationships.

An Investigation Item ends with one or more Knowledge Items, an Unknown, a Conflict, or an Ownership Gap. Unknowns, Conflicts, and Ownership Gaps are unresolved investigation outcomes rather than Knowledge Item categories.

One Interview Session result for an Investigation Item may contain several Knowledge Items alongside Unknowns, Conflicts, or Ownership Gaps. It becomes terminal when useful questioning ends, even when some parts remain unresolved.

An Ownership Gap records what remains unresolved, why the current interviewee cannot answer, and the named owner or owner description when known.

A Conflict links incompatible Knowledge Items and their Evidence, explains the incompatibility, and remains unresolved unless the Investigator decides between them. An Unknown records whether the interviewee did not know, declined, available Evidence was insufficient, or the required owner was unidentified.

FindWorks may infer an Assumption only from linked Evidence and must mark it unconfirmed for Investigator review. Knowledge Items have Investigator review states of unreviewed, accepted, corrected, or rejected. Acceptance means useful for the Discovery, not objectively true. Correction preserves the extracted claim and its Evidence, appends the Investigator correction as new Evidence, and creates a revised Knowledge Item version.

A Knowledge Item has stable identity and immutable versions. An Investigator correction marks the previous version corrected and creates an accepted revised version because the correction itself is an explicit review decision.

### Knowledge Item Link

An explicit `supports` or `qualifies` relationship between two Knowledge Items. Conflicts, correction lineage, and Evidence provenance use their own domain relationships rather than generic links.

### Evidence

The source material supporting a Knowledge Item. Every Knowledge Item links to exact Evidence containing its source type, Interview Session, participant, timestamp, and precise answer segment or attachment reference. Provenance does not expose model reasoning.

Evidence is immutable. One Evidence item may support several Knowledge Items, and one Knowledge Item may link to several Evidence items. M0 uses sources and human review rather than numeric confidence scores.

Each submitted answer or Investigator correction is a separate Evidence item linked to its source question and, when applicable, the Evidence it revises. Exact provenance links retain a validated quotation and character offsets into the immutable Evidence text.

Interview transcripts are reconstructed from ordered immutable Questions and Evidence rather than stored as duplicate transcript documents. A clarification Question links to the Question it clarifies, and only one Question is active in an Interview Session at a time.

### Findings Package

The Investigator-facing collection of Investigation Results produced for one Interview Session. Successful extraction creates an immutable Findings Package version, and the Investigator accepts or rejects one exact version.

## Agent/runtime direction already discussed

The preferred direction is to use **Pi (or an equivalent agent harness) as the Interview Session runtime**, rather than have the web application concatenate `SKILL.md` files into direct OpenAI API requests. Interview Mission Shaping runs separately in the Investigator's chosen project-aware harness.

The intended boundary is:

- FindWorks owns product/domain state, participants, missions, knowledge, provenance, persistence, permissions, and UX.
- The engineer's harness owns project context and the Interview Mission Shaping agent loop. It exchanges only stable semantic Mission and reviewed Discovery data with FindWorks through OAuth-authenticated MCP.
- Pi owns the Interview Session agent loop, skill loading, model/provider interaction, and tool execution.
- OpenAI can be a model provider behind Pi; it is not the FindWorks orchestration layer.
- A small custom Pi extension is likely to expose FindWorks-specific tools such as `ask_question`, `record_fact`, `record_assumption`, `record_conflict`, `record_unknown`, `create_investigation`, `delegate_investigation`, and `complete_interview`.
- FindWorks should expose a stable semantic protocol to its web UI rather than leaking raw Pi event shapes to the browser.

Treat the exact Pi integration mechanism (RPC process, SDK service, or another shape) as a decision still to be proven.

Current primary-source research shows that Pi supports TypeScript SDK embedding and stdin/stdout JSONL RPC, but no hosted service or daemon. A real Pi prototype validated one supervised RPC child per active Interview Session as the M0 runtime boundary, conditional on FindWorks remaining authoritative and the deployment providing hard process isolation. The exact semantic command, event, synchronisation, and recovery contract remains an architecture decision.

Pi process isolation is fault isolation, not a security sandbox. Any M0 integration must disable built-in filesystem and shell tools and ambient resources, explicitly load only the approved interview skill and trusted semantic-tool extension, translate raw Pi events behind the FindWorks adapter, and make application mutations authorised and idempotent. Pi session files are sensitive secondary checkpoints within Interview Session retention and deletion boundaries, never authoritative FindWorks state. A dedicated OS identity, sandbox, or container must prevent Pi and its trusted extension from reaching another Interview Session's files, processes, application credentials, or provider credentials.

For a live M0 interview turn, Pi receives one atomic `submit_interview_turn` capability. It may submit Evidence-linked outcome proposals plus exactly one next action: ask another question or propose completion. FindWorks alone accepts answers as Evidence, changes Interview Session lifecycle, sends invitations, and approves completion. Pi children stop after each settled turn and reopen from their secondary checkpoint for the next accepted input.

Each Pi child runs inside a hard per-Interview-Session sandbox or container with private session storage, a read-only runtime, restricted network access, and a short-lived FindWorks credential scoped to one runtime turn. M0 may share one centrally rotated model-provider credential across these isolated children; it does not introduce a custom credential gateway.

FindWorks advances Pi through one semantic runtime command containing a FindWorks-created run identifier, the approved Interview Mission version, the expected Interview Session revision, a minimal authoritative context projection, and one trigger: Session start, accepted Evidence, clarification request, or resume. The only control commands abort the current turn or shut down the child. One durable runtime-work record is created atomically with each accepted Evidence item. Execution is at least once, but one atomic turn commit is allowed per run identifier and each Interview Session advances serially. Stale revisions fail without mutation.

The stakeholder-facing runtime contract contains only `question_ready`, `completion_confirmation_ready`, and `runtime_failed`. A ready question includes plain-language progress. Raw Pi messages, tokens, tool calls, model errors, and retry details remain behind the runtime adapter. The interviewee sees a generic working state while the runtime starts, thinks, or retries.

Final findings extraction is a separate isolated runtime job built from the approved Interview Mission and immutable Evidence. It receives one atomic `submit_findings_package` capability. Interview Session completion never depends on extraction success, and a failed extraction reruns without reopening the interview or asking the interviewee to repeat answers.

Pi may retry a transient model failure up to three total attempts. A process death receives one supervised restart using the same run identifier. Exhaustion produces a recoverable `runtime_failed` result; the interviewee may retry, pause and leave, or end the interview without losing accepted Evidence or repeating an answer. Retry never creates a second semantic effect.

Every runtime start reconciles against FindWorks first. A committed turn replays its stable application event without another model call. An uncommitted turn may resume only from a checkpoint matching the Interview Session, approved Interview Mission version, and expected revision. Missing, corrupt, stale, or mismatched checkpoints are discarded and rebuilt from authoritative FindWorks context. FindWorks state is never reversed to match Pi.

Live Pi context contains only the approved shared Interview Mission, questions and accepted Evidence from the current Interview Session, current Investigation Item coverage and explicit outcomes, progress, and expected commitment. It excludes private Investigator context, other Interview Sessions, unrelated Discovery records, findings review, participant access data, email addresses, audit data, and operational metadata. Recovery may reconstruct the conversation from all accepted Evidence in that Interview Session.

## Architecture direction, not yet all decisions

A likely high-level shape is:

```text
Engineer harness -> FindWorks MCP
Web UI           -> FindWorks application/domain
       -> persistent relational store
       -> agent-runtime adapter
            -> Pi + FindWorks extension + skills
                 -> model provider
```

A conventional relational domain model is preferred over a generic entity/attribute system. A lightweight relationship table can express knowledge/investigation dependencies without introducing a graph database prematurely.

A modular monolith is the expected starting point unless Wayfinder surfaces evidence that another deployment shape is required.

## SaaS kernel

There is an existing separate Apache-2.0-licensed SaaS kernel project. Current research shows that it is a semantic workflow kernel rather than a reusable identity or SaaS platform layer: it provides tenant-scoped database and authorisation patterns, durable Intent/Event evidence, and telemetry, but not identity, membership, invitations, email, product-wide audit, retention, or deletion.

FindWorks M0 remains standalone with no shared-kernel runtime or library dependency. It may reuse proven ideas such as transaction-local tenant context, forced row-level security, least-privilege database roles, fail-closed authorisation, content-free telemetry, and forward-only migrations, but not the kernel's abstractions, schema, or semantic workflow model.

Discovery, Interview Mission, Interview Session, Investigation Item, Knowledge Item, and Evidence remain entirely FindWorks-owned if kernel adoption is reconsidered later. Reconsider only when FindWorks has a concrete need for the kernel's Action Offer and durable Intent/Event pipeline, the kernel supports deletion compatible with Discovery and Interview Session purging, a prototype shows value exceeding stack and operational cost, and its public released contract is stable enough to depend on. Kernel integration creates no M0 implementation work.

## M0 destination

The immediate goal is not to build the whole platform. Use Wayfinder to reach a **decision-complete specification for an M0 vertical slice**.

The candidate slice is:

```text
engineer harness shapes Interview Mission
  -> harness submits Discovery and draft Mission through FindWorks MCP
  -> investigator reviews and approves Interview Mission in FindWorks
  -> interviewee receives invitation
  -> adaptive Pi/skill interview runs
  -> structured findings return to investigator
```

The Wayfinder effort is complete when enough product, domain, UX, runtime, persistence, and integration decisions are settled that `/to-spec` and `/to-tickets` can proceed without implementation agents inventing product decisions.

M0 must specify a pilot-ready MCP and web experience for one organisation. One real Investigator must be able to shape a Mission in their chosen harness, create a Discovery and draft Interview Mission through FindWorks MCP, approve it in the web app, invite one real external interviewee, complete an adaptive Interview Session, review persisted Knowledge Items linked to Evidence, and retrieve reviewed Discovery context through MCP. The experience requires basic security and recoverable failure handling, but not full SaaS operational maturity.

M0 succeeds when the investigator confirms that the findings answer the Interview Mission's intent while exposing relevant unknowns, assumptions, conflicts, and ownership gaps. Automated coverage measures and interviewee approval may assist later, but they do not decide M0 success.

The first pilot exercises one investigator, one Interview Mission, one external interviewee, and one Interview Session. The Discovery model must permit later missions and interviewees, but M0 need not expose multi-interviewee orchestration.

The M0 Investigator persona is a software engineer. Investigator remains the canonical role because later Discoveries may be led by analysts, architects, product people, or others.

Before invitation, the Investigator's chosen harness conducts Interview Mission Shaping to identify the required Investigation Items and submits a reviewable draft Interview Mission through FindWorks MCP containing coverage and proposed opening questions. This is not a fixed questionnaire: questions remain adaptive during the Interview Session. The Investigator must explicitly approve the Interview Mission in FindWorks and choose to send the invitation; neither MCP nor FindWorks sends it automatically.

The harness may submit the draft Interview Mission only when its objective, intended interviewee, required Investigation Items, known context, boundaries, terminology, and completion criteria are explicit, with unresolved ambiguity exposed. The Investigator reviews all of those elements plus proposed opening questions in FindWorks. Before approval, they may edit the Mission or return to their harness for further shaping and submit a revised draft without restarting the Discovery.

An M0 Interview Mission contains its objective and desired outcome; intended interviewee and their relevance; required Investigation Items; shared known context; boundaries and prohibited topics; terminology; proposed opening questions; completion criteria; expected commitment; and data-use summary.

Each Investigation Item states the knowledge gap, why it matters, its priority, relevant known context, and acceptable outcomes such as supported knowledge, unknown, conflict, or ownership gap. It is not a fixed question. Required items must receive an explicit outcome before mission completion; optional items may remain uncovered.

Interview Mission Shaping classifies context as either shared with the interviewee or private to the Investigator. Only shared context crosses the MCP boundary into the draft Mission; private project and shaping context remains in the engineer's harness. Proposed opening questions are editable guidance for reviewing tone and direction, but do not constrain runtime wording or order.

Every generated Interview Mission element traces either to an Investigator statement or to an explicit agent proposal confirmed by the Investigator. The Investigator may approve only when every required section is complete and each unresolved ambiguity is resolved or deliberately represented by an Investigation Item.

A direct Investigator edit is authoritative and retains its author and timestamp. Requested regeneration is scoped to the affected sections and must not overwrite other deliberate edits. Approval freezes a specific Interview Mission version; changing any runtime-visible content or completion criterion revokes that approval.

Only the Investigator who owns the Discovery approves its Interview Mission in M0. Before approval, they may edit every Mission content field, including the intended interviewee. Provenance, timestamps, version, and lifecycle records remain system-controlled.

Sending an invitation is separate from approval and requires confirmation of the recipient email and approved Interview Mission version. The invitation remains bound to both. If the Mission changes before the Interview Session begins, the old invitation is revoked and the revised Mission requires approval and a new invitation. Once an Interview Session begins, its approved Mission version is immutable; later needs become follow-up Investigation Items rather than silent changes to the active interview.

Interview Mission versions have draft, approved, or superseded lifecycle states. Invitation delivery and Interview Session progress have separate lifecycles rather than being overloaded onto the Mission.

M0 sends the approved invitation by email. Before starting, the interviewee sees the Investigator's identity, organisation, Interview Mission purpose, expected commitment, a data-use summary, and an explicit choice to begin. They do not receive the Investigator's raw transcript or a fixed list of every proposed question.

The Investigator uses an authenticated account with verified email and membership in the pilot organisation. They may access all M0 records for Discoveries they own in that organisation, but nothing belonging to another organisation.

An external interviewee needs no account. Their emailed invitation is a single-use magic link bound to the exact recipient and approved Interview Mission version. It expires after seven days and exchanges once for a secure browser session that may resume on the same browser. Expired, revoked, previously redeemed, or lost-browser access requires Investigator reissue, which invalidates previous access.

The interviewee may access only the mission introduction, active question, their own previous answers, clarification flow, and completion acknowledgement. They cannot access private Investigator context, findings, other Discoveries or participants, or internal metadata.

Before explicitly starting, the interviewee sees the Investigator identity, purpose, expected commitment, data-use summary, retention period, and contact route. M0 does not add a dense legal workflow.

At pilot setup, the organisation selects one data-retention period, defaulting to 90 days after the Investigator accepts or rejects the findings package. A Discovery that never reaches review is deleted after 180 days without activity unless support extends it. The interviewee sees the applicable retention rule before starting. FindWorks warns the Investigator 14 days before deletion; manual support may extend it in M0.

The Investigator may delete an entire Discovery at any time. It becomes inaccessible immediately, content and derived data are purged within seven days, and encrypted backups age out within 30 days. An interviewee may request deletion through the displayed contact route; the Investigator may delete that Interview Session, its Evidence, and derived Knowledge Items while retaining the Discovery and reopening affected Investigation Items.

Audit records actor, time, organisation, resource, and outcome for authentication, Interview Mission approval, invitation and Interview Session lifecycles, findings extraction and review, access denial, retention changes, and deletion. Audit never contains answer text, Evidence content, invitation tokens, or model payloads. Minimal content-free audit tombstones remain for 12 months after content deletion, then delete automatically.

M0 accepts text answers only. The Evidence model may support attachment references later, but M0 has no file-upload feature.

M0 requires TLS, encryption at rest, hashed invitation tokens, secure browser cookies, rate limits, least privilege, and no secrets or interview content in logs. FindWorks records only that the interviewee controlled the invited email link; it does not claim real-world identity verification.

During the Interview Session, the interviewee sees one clear natural-language question at a time, brief human context when needed, an answer input, simple ways to say they do not know, name another owner, or request clarification, and simple progress and completion cues. They never tag or classify answers. FindWorks asks them to confirm a paraphrase only when an answer is ambiguous, contradictory, inferred, or materially important.

Structured findings and provenance review are exclusively Investigator-facing in M0. The interviewee receives a completion acknowledgement rather than a findings-review workflow.

M0 uses a calm, focused interaction with one primary task at a time and minimal surrounding chrome across Interview Mission approval, Interview Session, and findings review. The Interview Session includes a simple progress bar and plain-language coverage summary. Avoid document-first workspace navigation and dense multi-panel presentation as the default experience.

The Investigator receives findings grouped by Investigation Item. Each group shows its outcome, Knowledge Items, unresolved gaps, and review state; the package also shows mission-level coverage. Each claim shows a short source excerpt and participant, with one action to open the surrounding answer context or referenced attachment. The full transcript remains secondary Evidence rather than the primary findings view.

Before accepting the findings package, the Investigator reviews every required Investigation Item outcome; optional findings may remain unreviewed. They accept the package with notes or reject it and record required follow-up. Corrections append Investigator Evidence and never alter the interviewee's original Evidence.

Acceptance must show adaptive behaviour in both human interactions. Interview Mission Shaping in the engineer's harness asks answer-dependent follow-ups, exposes vague requirements, and converts clarified needs into Investigation Items. The Interview Session asks answer-dependent follow-ups, skips irrelevant planned questions, records uncertainty and ownership gaps, and stops only when every Investigation Item has an outcome.

During an Interview Session, the agent prioritises unresolved required Investigation Items, follows useful new information, clarifies ambiguity or contradiction, avoids already answered ground, and considers optional items only when required coverage and the expected commitment allow. It asks one plain-language question at a time, explains why only when useful, and adapts wording to the interviewee's vocabulary.

Submitting an answer immediately creates immutable Evidence. Later revisions append rather than overwrite. FindWorks may update internal candidate findings after each answer to guide follow-up questions.

When an answer is vague, uncertain, inferred, or contradictory, the agent asks focused clarification only while likely to help, then records an Assumption, Unknown, or Conflict rather than badgering. When the interviewee identifies another owner, it captures an Ownership Gap and owner details, stops pressing them, and never sends another invitation automatically.

The agent may restate approved shared context and clarify its question. It must never supply the domain answer, resolve a Conflict itself, or speak for the Investigator or interviewee.

FindWorks tracks each Investigation Item internally as unaddressed, exploring, or having an explicit outcome. The interviewee sees only simple progress language. The agent monitors expected commitment, prioritises required items, and near the limit asks whether the interviewee wants to continue or finish.

The interviewee may pause, resume, request clarification, revise an earlier answer, or end at any time. The agent proposes normal completion only when every required Investigation Item has an explicit outcome, gives a brief natural-language recap of unresolved points, and asks the interviewee to confirm. Unknown, Conflict, and Ownership Gap count as explicit outcomes rather than false resolution.

If the interviewee stops before required coverage, the Interview Session ends early, preserves all Evidence, and exposes remaining Investigation Items to the Investigator. A normally completed session still flags Investigator follow-up for any required Unknown, Conflict, or Ownership Gap. Repeated clarification without useful new information, or interviewee discomfort, ends that line of questioning with the best honest unresolved outcome.

The agent pursues emergent information only when it helps resolve approved Investigation Items and remains within Interview Mission boundaries and expected commitment. Other useful information becomes a suggested follow-up rather than silently expanding the Mission.

If an interviewee volunteers out-of-scope content, FindWorks retains the submitted answer as Evidence but does not follow or extract it unless relevant to the Mission. The existing deletion route remains available.

An Interview Session becomes complete when the interviewee confirms finish, independently of later findings extraction. FindWorks then extracts final candidate findings from retained Evidence and tracks that processing separately as pending, ready, or failed. Extraction failure retries without changing Session state, reopening the interview, or asking the interviewee to repeat answers.

The Investigator may revoke access and terminate an active Interview Session. Existing Evidence is preserved and the Session is marked terminated; the Investigator can never edit the interviewee's answers.

M0 ends with investigator review of findings and their provenance inside FindWorks. Exports, generated specifications, and downstream delivery-tool integrations are outside this effort.

The first pilot may handle ordinary confidential business information. Its specification must settle access control, retention, deletion, and audit behaviour, but must not claim support for regulated or highly sensitive data.

Only investigator and interviewee require product UI in M0. Organisation setup and support intervention may use documented manual operations.

M0 acceptance is anchored in a real requirements and domain discovery for a bounded software change. Evidence must combine one live end-to-end pilot with repeatable scripted checks for agreed boundary and failure scenarios.

The investigator may accept the result only when every Interview Mission area ends as supported knowledge, an explicit unknown, a conflict, or an ownership gap; every asserted Knowledge Item links to Evidence; and no critical fabrication or misattribution exists.

Boundary checks must cover an interviewee who does not know, identifies another owner, contradicts known context, revises an earlier answer, or offers an unsupported assumption. Failure checks must cover an expired or reused invitation, an interrupted Interview Session, a model or runtime failure, and a findings extraction failure. Accepted answers must never disappear, duplicate, or become detached from their Evidence.

An expired or reused invitation must fail safely and allow reissue. An interrupted Interview Session resumes after its last accepted answer. Runtime failure retries or resumes without duplicating work. Findings extraction can rerun from retained Evidence without another interview.

Any fabricated Knowledge Item, wrong Evidence attribution, cross-participant disclosure, lost accepted answer, recovery duplication, premature Interview Session completion, or invitation bypass automatically fails M0 acceptance. The acceptance record retains the approved Interview Mission, invitation lifecycle, timestamped Evidence, structured findings with provenance, scripted check results, and the Investigator's acceptance decision with notes.

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
