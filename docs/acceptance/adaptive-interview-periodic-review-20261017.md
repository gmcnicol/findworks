# Adaptive Interview periodic-review spike — 2026-10-17

## Decision

**NO-GO. Keep the single `openai/gpt-5.4-mini` / low-reasoning interviewer. Do not add a production reviewer or a general multi-agent framework.**

The reviewer-assisted strategy produced more useful Evidence per Question and one fewer completion error, but it failed the predeclared adoption gates:

- runs with deterministic failures increased from 5 to 6;
- 8 of 42 reviewer proposals were rejected before they could affect interviewer context;
- median provider-reported cost per Question was 2.44× baseline, above the 2× ceiling;
- p95 end-to-end Runtime Turn latency was 2.33× baseline, above the 2× ceiling.

Both strategies missed zero fixture-expected Conflicts, so this run provides no evidence that a separate reviewer improves conflict detection. The result is deliberately conservative: one trial of the complete shared suite is enough to reject this spike under its gates, not enough to claim universal model behaviour.

The sanitised machine-readable evidence, including configured metadata, per-scenario metrics, reviewer activity, and every retained synthetic failure, is [`traces/issue-67-periodic-review-26eabe6.json`](./traces/issue-67-periodic-review-26eabe6.json).

**Historical-evidence correction (no rerun):** the retained run did not capture provider/request/response model identity for the baseline interviewer, reviewed interviewer, or reviewer. Its shared model and reasoning values are configuration, while the dated response model below came from a separate probe. They do not prove that all three roles actually used one model snapshot. The paired run therefore cannot establish same-model role isolation and must not be used as corrected comparative evidence. The NO-GO remains a conservative product decision because the candidate already failed its declared safety, cost, and latency gates; it is not a claim about reviewer quality under a verified same-model comparison. A fresh credentialed run through the corrected evaluator is required for that claim.

## Evaluated design

The architecture and versioned request/response contract are in [`../architecture/periodic-investigation-review-spike.md`](../architecture/periodic-investigation-review-spike.md).

- The interviewer alone asks the next Question and submits the Runtime Turn.
- The reviewer only inspects the approved Mission projection, immutable Session Evidence, Investigation Results, conflicts, sufficiency, and next-thread priority.
- FindWorks creates the request and validates request identity, Session, exact Mission version, expected revision, Evidence digest, result scope, Evidence provenance, completion assessment, allowed fields, and idempotent replay before attaching `ADVISORY_ONLY` guidance.
- An invalid or stale proposal cannot affect interviewer context. The ordinary `submit_interview_turn` contract remains authoritative.
- No production persistence, autonomous delegation, reviewer memory, agent negotiation, or reusable multi-agent framework was added.

The tested cadence ran after initial Evidence, explicit uncertainty/refusal/ownership answers, a thread change, or four revisions since the previous accepted review. It did not run on every turn.

## Reproducibility metadata

- Evaluated implementation commit: `26eabe672a0dc8bfaccdf73baf194544efb477dc`.
- Worktree at evaluation start: clean.
- Runtime image: `findworks-runtime:ticket-67`.
- Runtime image ID: `sha256:291c5187abfe01f4845426da840ffefde3b24e21f8dfa79bf12258cd5d85ab91`.
- Runtime image revision label: `26eabe672a0dc8bfaccdf73baf194544efb477dc` (matched the evaluated commit).
- Pi runtime: `0.84.4`.
- Configured provider: OpenAI.
- Configured requested model: `gpt-5.4-mini`.
- Provider-resolved model from a separate minimal live probe: `gpt-5.4-mini-2026-03-17`; this was not captured from the three execution roles.
- Configured reasoning effort: `low` for baseline interviewer, reviewed interviewer, and reviewer.
- Fixture schema: 1.
- Fixtures: all 18 JSON fixtures under `runtime/evaluation/fixtures/v1`, at the exact versions retained in the machine evidence.
- Repeats: 1 per strategy.
- Evidence: synthetic people and operations only. No provider payload, raw Pi session, credential, provider project identity, or production Interview Session content is retained.

The OpenAI credential was read from `findworks-pilot-worker-1` only into command environments. It was not printed, logged, written to the report, or persisted in the repository.

## Historical paired results

These values describe the retained run only. In addition to the corrected outcome-metric limitations below, missing role-specific actual model identities invalidate the run as proof of a controlled same-model comparison.

| Measure | Single interviewer | Interviewer + periodic reviewer | Change |
| --- | ---: | ---: | ---: |
| Scenario runs | 18 | 18 | — |
| Runtime Turns | 84 | 82 | -2 |
| Useful Evidence / Question | 0.6043 | 0.6878 | +0.0835 |
| Missed expected Conflicts | 0 | 0 | no change |
| Completion errors | 5 | 4 | -1 |
| Early completions | 0 | 0 | no change |
| Repeated Questions | 11 | 8 | -3 |
| Runs with deterministic failures | 5 | 6 | +1 |
| Required semantic-gate failures | 10 | 10 | no change |
| Continuity-gate failures | 5 | 4 | -1 |
| Required-coverage completion, mean | 86.11% | 91.67% | +5.56 points |
| Semantic pass rate, pooled | 87.89% | 88.82% | +0.92 points |
| Turns to sufficient Evidence, median | 2 | 2 | no change |
| Turn latency p50 / p95 | 4.90 s / 6.47 s | 9.68 s / 15.08 s | 1.98× / 2.33× |
| Provider-reported total cost | $0.284804 | $0.512987 | 1.80× |
| Median cost / Question | $0.002173 | $0.005298 | 2.44× |
| Input tokens | 407,001 | 525,984 | +29.2% |
| Output tokens | 22,797 | 53,866 | +136.3% |
| Cache-write tokens | 0 | 0 | no change |

Provider cost is reported in USD. The median values and 2.4381363244176022× calculation above were re-derived from the retained per-scenario provider costs without rerunning the model; rounding is display-only. This correction does not change the #67 NO-GO because the ratio remains above the predeclared 2× ceiling, and the reliability and latency gates also still fail.

The retained run predates the corrected production-rejection and semantic-outcome qualification rules. Its useful-Evidence, required-coverage, and turns-to-sufficiency values are therefore historical observations, not valid comparative evidence; they are marked accordingly in the machine record and require a fresh credentialed rerun. This removes the claimed positive Evidence-yield signal but does not weaken or change any predeclared #67 gate.

## Reviewer cadence and safeguards

- Reviewer invocations: 42 across 82 reviewed-strategy Runtime Turns (51.2%).
- Accepted proposals: 34.
- Rejected proposals: 8.
- Triggers: 19 initial-Evidence, 16 periodic, 6 thread-boundary, 1 uncertainty.
- Reviewer-only latency: p50 6.93 s; p95 12.01 s.
- Rejection reasons: 4 out-of-scope result/thread proposals, 3 out-of-scope Evidence/provenance proposals, and 1 inconsistent completion assessment.

Those rejections are a successful safeguard result—the proposals did not enter interviewer context—but an operational and reliability failure for the candidate. The 51.2% invocation rate was also too high to qualify as cheap occasional oversight in this implementation.

## Interpretation

Historically, the split role appeared to move Evidence yield, required coverage, completion correctness, continuity, and repetition in the favourable direction. The corrected outcome gate and missing actual role identities mean those movements are not valid comparative evidence. The retained run missed no expected Conflict in either strategy and still failed the candidate gates:

1. reviewer output was not reliably contract-valid even with a narrow schema and explicit responsibilities;
2. the extra model call approximately doubled median latency and more than doubled median cost per Question;
3. rejected reviews created an additional deterministic-failure run;
4. required semantic quality did not improve.

A future reconsideration should begin with a cheaper deterministic coverage/conflict check or a much narrower reviewer trigger, not a general second agent. Another model-backed review should be attempted only after a concrete fixture failure shows information unavailable to deterministic server checks.

## Commands and tests

Evaluation command:

```bash
docker build \
  --build-arg VCS_REF="$(git rev-parse HEAD)" \
  -t findworks-runtime:ticket-67 \
  runtime

FINDWORKS_RUNTIME_IMAGE=findworks-runtime:ticket-67 \
  npm run evaluate:periodic-review -- \
  --commit "$(git rev-parse HEAD)" \
  --model gpt-5.4-mini \
  --reasoning low \
  --repeats 1 \
  --output artifacts/periodic-review-evaluation/issue-67
```

Credential-free verification passed:

- `npm run test:runtime` — 79 tests passed before the reporting-distribution regression test was added; the final run contains 80 tests.
- `source scripts/use-java-25.sh && mvn test -q` — passed.
- `docker build --build-arg VCS_REF="$(git rev-parse HEAD)" -t findworks-runtime:ticket-67 runtime` — passed.

No behavioural assertion was weakened. The existing shared fixtures and their semantic gates were used unchanged.
