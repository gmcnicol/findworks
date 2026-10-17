# Adaptive Interview evaluation harness

The model-backed harness exercises the production Pi image, `submit_interview_turn` tool, and HTTP Runtime Turn submission seam. It does not replace deterministic Runtime Turn controller tests. It evaluates complete synthetic Interview Missions and preserves the original three-answer morning-sort sequence as the `morning-sort` fixture.

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

The command exits non-zero for a deterministic contract violation, a fixture's required semantic gate, or regression against the selected baseline. The preserved `morning-sort` acceptance fixture requires every original semantic assertion to pass; those checks remain semantically classified rather than being mislabeled as deterministic transport rules. Ordinary credential-free checks remain available through:

```bash
npm run test:runtime
```

## Results

Each output directory contains:

- `result.json`: the machine-readable report and complete review trace;
- `summary.md`: a human-readable result per scenario and repeat.

The report pins the Git commit, dirty-worktree state, provider/model, reasoning effort, image and image ID, Pi runtime version when labelled or supplied, fixture versions, duration, repeats, and token usage/cost when the runtime makes those values available. Unavailable usage or cost is recorded as `null`, not estimated.

Every turn records the source Question and answer, submitted action, outcome summaries and exact Evidence identities, coverage changes, repeated-Question detection, runtime event, latency, and any available usage. A contract failure includes the turn, stable rule name, source answer, and offending action.

Deterministic contract checks are reported separately from graded semantic checks:

- **Contract:** run/revision identity, result and Evidence scope, supported coverage/category/mode, one next action, current-thread continuity, Evidence-backed thread transitions, required-before-optional priority, no continuation or reopening of terminal threads, one Question, repeat detection, and no premature completion.
- **Semantic quality:** interviewee vocabulary, private Mission language, own-words response, one information need, non-leading wording, concision, explicit expected-open coverage, expected outcome kind, and provenance/summary expectations.

A runtime `PROPOSE_COMPLETION` ends that scenario immediately, just as it ends live questioning. If fixture turns remain, the existing expected-action or premature-completion violation is retained; the harness never fabricates a later Question after completion.

The checked-in baseline is an explicit quality floor for `openai/gpt-5.4-mini` at low reasoning. Replace it with a reviewed `result.json`, or pass any prior result with `--baseline`, after a credentialed full-suite run. Baseline comparison never changes the individual behavioural checks.

## Extending fixtures

Fixtures live in `runtime/evaluation/fixtures/v1/`. Add another `.json` file; harness logic and scenario registration do not change. Each fixture contains:

- `schemaVersion`, independently reviewable `fixtureVersion`, and an optional `minimumSemanticPassRate`;
- a synthetic Interview Mission, Investigation Results, and authoritative Runtime Turn context;
- an interviewee persona;
- an ordered answer sequence with a distinct Runtime Turn run identity, the `sourceResultId` of the Question being answered, immutable Evidence identities, and source types;
- expected action, result, semantic qualities, explicit results that must remain open, and Evidence-linked outcomes.

The current set covers adaptive follow-ups, successful completion, Unknown, Ownership Gap, Conflict, unconfirmed Assumption, mere mention without sufficiency, parked information and later thread transition, required-before-optional priority, and explicit reuse of one rich Evidence item across multiple results. Advance the fixture directory/schema only for an incompatible fixture contract; increment an individual fixture version when its Mission, persona, answers, or expectations change.
