# Adaptive Interview evaluation harness

The model-backed harness exercises the production Pi image, `submit_interview_turn` tool, and HTTP Runtime Turn submission seam. It does not replace deterministic Runtime Turn controller tests. It evaluates complete synthetic Interview Missions and preserves the original three-answer morning-sort sequence as the `morning-sort` fixture.

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

`OPENAI_API_KEY` or `FINDWORKS_MODEL_OAUTH_B64` must be set for the baseline provider. If Docker is reached through the deployed worker, also set `FINDWORKS_DOCKER_HOST_CONTAINER`. Use `--repeats 3` or `FINDWORKS_EVALUATION_REPEATS=3` to expose model variance. Use `--scenario morning-sort` for the former three-turn smoke test alone. `--provider`, `--fixtures`, `--baseline`, `--runtime-version`, and repeated `--scenario` options support other pinned runs; `--help` lists all options.

The command exits non-zero for a deterministic contract violation, a fixture's required semantic gate, a continuity/provenance gate, or regression against the selected baseline. The preserved `morning-sort` acceptance fixture requires every original semantic assertion to pass; those checks remain semantically classified rather than being mislabeled as deterministic transport rules. Ordinary credential-free checks remain available through:

```bash
npm run test:runtime
```

## Results

Each output directory contains:

- `result.json`: the machine-readable report and complete review trace;
- `summary.md`: a human-readable result per scenario and repeat.

The report pins the Git commit, dirty-worktree state, provider/model, reasoning effort, image and image ID, Pi runtime version when labelled or supplied, fixture versions, duration, repeats, and token usage/cost when the runtime makes those values available. Unavailable usage or cost is recorded as `null`, not estimated.

Every turn records the source Question and answer, synthetic stressor, submitted action, outcome summaries and exact Evidence identities, coverage changes, exact or semantic repeated-Question detection, records removed from the visible context window, runtime event, latency, and any available usage. A contract failure includes the turn, stable rule name, source answer, and offending action. These fixtures contain synthetic people and operations only; do not add production transcripts, credentials, provider payloads, or raw Pi session files to retained traces.

Per scenario, the report measures required-coverage progress, answers linked to useful outcome Evidence per Question, p50/p95 turn latency, compaction retention, and early/middle/late semantic quality. The context-growth section explicitly reports late-versus-early quality and late failing rules so degradation is visible rather than hidden in one aggregate pass rate. Token use and provider-reported cost remain in the report metadata.

Deterministic contract checks are reported separately from graded semantic checks:

- **Contract:** run/revision identity, result and Evidence scope, supported coverage/category/mode, one next action, current-thread continuity, Evidence-backed thread transitions, required-before-optional priority, no continuation or reopening of terminal threads, one Question, exact and semantic repeat detection, no private Mission language, no configured fabricated facts, no unsupported names or numeric claims, grounded choice labels, and no premature completion.
- **Semantic quality:** interviewee vocabulary, private Mission language, own-words response where an open answer is required, one information need, non-leading wording, concision, explicit expected-open coverage, expected outcome kind, forbidden unsupported outcome kinds, and provenance/summary expectations. Fixtures may score a useful follow-up focus, pressure or owner-recruitment language, every required summary qualifier, or reviewed wording alternatives; each result remains visible in the turn trace.

A runtime `PROPOSE_COMPLETION` ends that scenario immediately, just as it ends live questioning. If fixture turns remain, the existing expected-action or premature-completion violation is retained; the harness never fabricates a later Question after completion.

The checked-in baseline is an explicit quality floor for `openai/gpt-5.4-mini` at low reasoning. Replace it with a reviewed `result.json`, or pass any prior result with `--baseline`, after a credentialed full-suite run. Baseline comparison never changes the individual behavioural checks.

## Reproducible model benchmark

The benchmark command runs the same fixture versions and repeat count through every configuration in `runtime/evaluation/benchmark-configurations.v1.json`:

```bash
FINDWORKS_RUNTIME_IMAGE=findworks-runtime:evaluation npm run benchmark:adaptive-interview -- \
  --commit "$(git rev-parse HEAD)" \
  --repeats 3 \
  --output artifacts/adaptive-interview-model-benchmark/current
```

The versioned portfolio requires `openai/gpt-5.4-mini` at low reasoning and `openai/gpt-5.4` at medium reasoning, and also explores mini at medium reasoning. Before spending the benchmark budget, the command queries the provider's model list and makes a minimal Responses API request with every exact model/reasoning pair. The safe preflight record retains the requested and provider-resolved model IDs, HTTP status, and error code/type, but never the credential, provider project identity, or raw error message. A configuration without verified access and reasoning support is marked blocked rather than silently substituted. Provider capability documentation is pinned in the configuration manifest; the live probe is the authority for the configured credential.

The combined `benchmark.json` and `summary.md` retain aggregate distributions for semantic quality, required coverage, useful Evidence per Question, turns to sufficient Evidence, latency, cost per run, and cost per Question. They separately count missed contradictions, repeated Questions, and early completion and retain each sanitised synthetic contract or semantic failure. Per-configuration raw traces remain under the benchmark output directory. Do not put real Interview Session content or provider payloads there.

The baseline remains mini/low unless a candidate has no deterministic, required semantic, continuity, missed-contradiction, repeated-Question, or early-completion failures; improves lower-quartile semantic quality by at least five percentage points; loses no more than two points of median useful Evidence per Question; costs no more than twice as much per useful Evidence; and has no more than twice the p95 latency. This is a benchmark decision only. It does not add production routing or model switching.

## Extending fixtures

Fixtures live in `runtime/evaluation/fixtures/v1/`. Add another `.json` file; harness logic and scenario registration do not change. Each fixture contains:

- `schemaVersion`, independently reviewable `fixtureVersion`, and an optional `minimumSemanticPassRate`;
- a synthetic Interview Mission, Investigation Results, and authoritative Runtime Turn context;
- an interviewee persona;
- an ordered answer sequence with a distinct Runtime Turn run identity, the `sourceResultId` of the Question being answered, immutable Evidence identities, and source types;
- expected action, result, semantic qualities, explicit results that must remain open, and Evidence-linked outcomes.

The current set covers adaptive follow-ups, successful completion, Unknown, Ownership Gap, Conflict, unconfirmed Assumption, vague and partial answers, uncertain recollection, contradictions within the scripted interview and against already collected Evidence, disputed terminology, incomplete owner details, explicit `I don't know`, refusal, mere mention without sufficiency, parked information and later thread transition, required-before-optional priority, explicit reuse of one rich Evidence item across multiple results, and 20/40-turn continuity outcomes. The ambiguity scenarios require a 100% semantic gate as well as the ordinary deterministic contract checks.

Long fixtures may also declare `assertions.minimumTurns`, private/fabricated terms, critical Evidence identities, per-turn `stressor` labels, and `contextPolicy.maxQuestionEvidenceRecords`. Advance the fixture directory/schema only for an incompatible fixture contract; increment an individual fixture version when its Mission, persona, answers, or expectations change.
