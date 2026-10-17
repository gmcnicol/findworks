# Adaptive Interview evaluation harness

The model-backed harness exercises the production Pi image, `submit_interview_turn` tool, and HTTP Runtime Turn submission seam. The evaluation HTTP adapter now enforces the production Runtime Turn validation, rejection, and atomic-commit contract against synthetic authoritative state: a rejected proposal is returned to Pi for repair, retained in the trace, and never applied. It does not replace PostgreSQL-backed Runtime Turn controller tests. The harness evaluates complete synthetic Interview Missions and preserves the original three-answer morning-sort sequence as the `morning-sort` fixture.

Two continuity fixtures extend that seam: `claims-routing-20-turn-continuity` runs 20 turns without compaction, while `permit-handover-40-turn-compaction` runs 40 turns with the visible Question/Evidence window capped at 12 records. Both include long answers, vocabulary changes, interruptions, volunteered cross-thread details, and explicit revisions of earlier statements. The compacted fixture requires critical earlier Evidence to remain represented by durable `current_outcomes`; silently dropping it fails the continuity gate.

## Run locally

Build the image from the commit being evaluated:

```bash
docker build \
  --build-arg VCS_REF="$(git rev-parse HEAD)" \
  -t findworks-runtime:evaluation \
  runtime
```

With model credentials available to Docker, this one command runs every scenario once and writes both result formats:

```bash
FINDWORKS_RUNTIME_IMAGE=findworks-runtime:evaluation npm run test:adaptive-interview -- \
  --commit "$(git rev-parse HEAD)" \
  --model gpt-5.4-mini \
  --reasoning low \
  --output artifacts/adaptive-interview-evaluation/current
```

`OPENAI_API_KEY` must be set for the `openai` provider. OAuth is never substituted silently; an OAuth run must explicitly select its matching provider, and the benchmark manifest intentionally uses the API-key provider. Only the selected provider credential is passed into each container. If Docker is reached through the deployed worker, also set `FINDWORKS_DOCKER_HOST_CONTAINER`. Use `--repeats 3` or `FINDWORKS_EVALUATION_REPEATS=3` to expose model variance. Use `--scenario morning-sort` for the former three-turn smoke test alone. `--provider`, `--fixtures`, `--baseline`, `--runtime-version`, and repeated `--scenario` options support other pinned runs; `--help` lists all options.

The command exits non-zero for a deterministic contract violation, a fixture's required semantic gate, a continuity/provenance gate, or regression against the selected baseline. The preserved `morning-sort` acceptance fixture requires every original semantic assertion to pass; those checks remain semantically classified rather than being mislabeled as deterministic transport rules. Ordinary credential-free checks remain available through:

```bash
npm run test:runtime
```

## Results

Each output directory contains:

- `result.json`: the machine-readable report and complete review trace;
- `summary.md`: a human-readable result per scenario and repeat.

The report pins the Git commit, dirty-worktree state, requested and provider-response model identities, configured Pi reasoning effort, explicit credential path (never the credential), image and image ID, Pi runtime version, fixture versions, duration, repeats, and token usage/cost when the runtime makes those values available. Pi 0.84.4 assistant messages supply the actual provider, requested model, and provider-response model; the runner records the reasoning effort it passed to Pi rather than inventing a provider-returned reasoning field. Every actual Runtime Turn must retain one model identity, and benchmark runs require its provider-response model, configured reasoning, and credential path to match the same-credential preflight; an alias change, mismatch, mixed identity, missing identity, or OAuth/API-key substitution invalidates the run. Unavailable usage or cost is recorded as `null`, not estimated.

Every turn records the source Question and answer, synthetic stressor, submitted action, outcome summaries and exact Evidence identities, coverage changes, exact or semantic repeated-Question detection, records removed from the visible context window, runtime event, latency, and any available usage. A contract failure includes the turn, stable rule name, source answer, and offending action. These fixtures contain synthetic people and operations only; do not add production transcripts, credentials, provider payloads, or raw Pi session files to retained traces.

Per scenario, the report measures required-coverage progress, answers linked to useful outcome Evidence per Question, p50/p95 turn latency, compaction retention, and early/middle/late semantic quality. Coverage, useful Evidence, and turns to sufficient Evidence advance only for server-accepted, in-scope outcomes that match the fixture's independently declared result, outcome kind, provenance, and required semantic anchors. Invalid, fabricated, forbidden, or wrong-result proposals remain failures and cannot improve those metrics. The context-growth section explicitly reports late-versus-early quality and late failing rules so degradation is visible rather than hidden in one aggregate pass rate. Token use and provider-reported cost remain in the report metadata.

Deterministic contract checks are reported separately from graded semantic checks:

- **Contract:** run/revision identity, result and Evidence scope, supported coverage/category/mode, one next action, current-thread continuity, Evidence-backed thread transitions, required-before-optional priority, no continuation or reopening of terminal threads, one Question, exact and semantic repeat detection, no private Mission language, no configured fabricated facts, no unsupported names or numeric claims, grounded choice labels, and no premature completion.
- **Semantic quality:** interviewee vocabulary, private Mission language, own-words response where an open answer is required, one information need, non-leading wording, concision, explicit expected-open coverage, expected outcome kind, forbidden unsupported outcome kinds, and provenance/summary expectations. Fixtures may score a useful follow-up focus, pressure or owner-recruitment language, every required summary qualifier, or reviewed wording alternatives; each result remains visible in the turn trace.

A runtime `PROPOSE_COMPLETION` ends that scenario immediately, just as it ends live questioning. If fixture turns remain, the existing expected-action or premature-completion violation is retained; the harness never fabricates a later Question after completion.

The checked-in baseline is derived from the retained credentialed #67 single-interviewer mini/low strategy, the newest retained run with the current fixture portfolio, with its source commit and limitations recorded. Comparison is allowed only when report schema, exact fixture IDs and versions, internally consistent source/runtime commits, Pi runtime, requested and provider-resolved model, configured reasoning, and credential kind/path match. The historical resolved model came from its retained minimal live probe because that run predates actual-turn identity capture. The baseline therefore retains only the semantic and deterministic floor available from that archive; affected coverage, useful-Evidence, sufficiency, model-identity confirmation, and cost trade-offs require a fresh credentialed run. Baseline comparison never changes the individual behavioural checks.

## Reproducible model benchmark

The benchmark command runs the same fixture versions and repeat count through every configuration in `runtime/evaluation/benchmark-configurations.v1.json`:

```bash
FINDWORKS_RUNTIME_IMAGE=findworks-runtime:evaluation npm run benchmark:adaptive-interview -- \
  --commit "$(git rev-parse HEAD)" \
  --repeats 3 \
  --output artifacts/adaptive-interview-model-benchmark/current
```

The versioned portfolio requires `openai/gpt-5.4-mini` at low reasoning and `openai/gpt-5.4` at medium reasoning, and also explores mini at medium reasoning. Before spending the benchmark budget, the command queries the provider's model list, makes a minimal Responses API request with every manifest model/reasoning pair, and repeats that probe against any distinct provider-resolved model ID through the same selected API-key credential path used by Pi. The safe preflight record retains the manifest-requested and provider-resolved model IDs, exact-model verification, reasoning setting, credential kind/path, HTTP status, and error code/type, but never the credential, provider project identity, or raw error message. Each accessible evaluation then gives Pi the exact provider-resolved model ID rather than reusing the manifest alias. Because Pi 0.84.4's bundled catalogue names these models by alias, the isolated runtime writes matching non-secret model metadata for the dated GPT-5.4 mini/full snapshot into its ephemeral Pi configuration; the provider credential remains environment-only. Every actual Pi assistant message reports provider, requested model, and provider-response model; the runner separately records the reasoning effort configured on Pi 0.84.4. Both actual-turn model identities must equal the preflight's resolved model, and configured reasoning and credential path must match the same-credential preflight throughout the run. A configuration without verified exact-model access/reasoning, or with an actual-turn identity mismatch, is blocked or invalid rather than silently substituted. One configuration's evaluation failure is retained as failed without aborting or substituting the remaining configurations. Provider capability documentation is pinned in the configuration manifest; preflight plus actual-turn identity is authoritative for the configured credential.

The combined `benchmark.json` and `summary.md` retain aggregate distributions for semantic quality, required coverage, useful Evidence per Question, turns to sufficient Evidence, latency, cost per run, and cost per Question. Calculations retain full numeric precision; rounding occurs only while rendering Markdown. They separately count missed contradictions, repeated Questions, and early completion and retain each sanitised synthetic contract or semantic failure. Per-configuration raw traces remain under the benchmark output directory. Do not put real Interview Session content or provider payloads there.

The baseline remains mini/low unless a candidate has no deterministic, required semantic, continuity, missed-contradiction, repeated-Question, or early-completion failures; improves lower-quartile semantic quality by at least five percentage points; loses no more than two points of median useful Evidence per Question; costs no more than twice as much per useful Evidence; and has no more than twice the p95 latency. This is a benchmark decision only. It does not add production routing or model switching.

## Periodic investigation review spike

Issue #67 reuses this exact fixture suite to compare the single interviewer with the same interviewer receiving occasional, separately generated review advice. The architecture and versioned request/response proposal contract are documented in [`../architecture/periodic-investigation-review-spike.md`](../architecture/periodic-investigation-review-spike.md).

Build the evaluated image, then run the paired strategies with the same model and reasoning effort:

```bash
docker build \
  --build-arg VCS_REF="$(git rev-parse HEAD)" \
  -t findworks-runtime:periodic-review \
  runtime

FINDWORKS_RUNTIME_IMAGE=findworks-runtime:periodic-review \
  npm run evaluate:periodic-review -- \
  --commit "$(git rev-parse HEAD)" \
  --model gpt-5.4-mini \
  --reasoning low \
  --output artifacts/periodic-review-evaluation/current
```

The paired `result.json` and `summary.md` pin commit, dirty state, image and image ID, Pi runtime, provider/model/reasoning, fixture versions, repeats, token usage and provider-reported cost. They retain the baseline and reviewed synthetic traces, role-specific usage and latency, rejected reviewer proposals, reviewer triggers/cadence, and the predeclared go/no-go decision. The reviewer proposal gate runs before advice enters interviewer context; ordinary Runtime Turn validation remains unchanged and no behavioural assertion is weakened.

## Extending fixtures

Fixtures live in `runtime/evaluation/fixtures/v1/`. Add another `.json` file; harness logic and scenario registration do not change. Each fixture contains:

- `schemaVersion`, independently reviewable `fixtureVersion`, and an optional `minimumSemanticPassRate`;
- a synthetic Interview Mission, Investigation Results, and authoritative Runtime Turn context;
- an interviewee persona;
- an ordered answer sequence with a distinct Runtime Turn run identity, the `sourceResultId` of the Question being answered, immutable Evidence identities, and source types;
- expected action, result, semantic qualities, explicit results that must remain open, and Evidence-linked outcomes.

The current set covers adaptive follow-ups, successful completion, Unknown, Ownership Gap, Conflict, unconfirmed Assumption, vague and partial answers, uncertain recollection, contradictions within the scripted interview and against already collected Evidence, disputed terminology, incomplete owner details, explicit `I don't know`, refusal, mere mention without sufficiency, parked information and later thread transition, required-before-optional priority, explicit reuse of one rich Evidence item across multiple results, and 20/40-turn continuity outcomes. The ambiguity scenarios require a 100% semantic gate as well as the ordinary deterministic contract checks.

Long fixtures may also declare `assertions.minimumTurns`, private/fabricated terms, critical Evidence identities, per-turn `stressor` labels, and `contextPolicy.maxQuestionEvidenceRecords`. Advance the fixture directory/schema only for an incompatible fixture contract; increment an individual fixture version when its Mission, persona, answers, or expectations change.
