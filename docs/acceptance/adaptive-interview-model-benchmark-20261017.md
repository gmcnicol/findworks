# Adaptive Interview model benchmark — 2026-10-17

## Decision

Retain `openai/gpt-5.4-mini` with low reasoning as the default. Do not add production model routing.

`gpt-5.4-mini` at medium reasoning improved some quality and coverage measures, but did not meet the predeclared promotion criterion. The required `gpt-5.4` medium comparison is blocked because the configured OpenAI project does not currently have access to that model. Retaining the current default is therefore a conservative recommendation, not evidence that the full model is inferior.

The sanitised machine-readable report, including every individual contract and semantic failure, is [issue-66-model-benchmark-6a66aeb.json](./traces/issue-66-model-benchmark-6a66aeb.json).

## Reproducibility and provider verification

- Evaluated commit: `6a66aebe46761bcec07cff5bb2a284023f598fc8`.
- Runtime: Pi `0.84.4`; pinned image commit matched the evaluated commit.
- Configuration manifest: `runtime/evaluation/benchmark-configurations.v1.json` version `1.0.0`.
- Fixture suite: all 18 versioned synthetic scenarios from `runtime/evaluation/fixtures/v1`, three trials per accessible configuration.
- The run used synthetic people and operations only. The retained comparison omits credentials, provider project identity, raw provider errors, and absolute host paths.
- OpenAI's model documentation identifies `gpt-5.4-mini` and `gpt-5.4` as exact API model IDs and documents low and medium reasoning support for both. The benchmark then checked the configured credential rather than relying on that documentation or Pi's catalogue.
- Live model discovery listed `gpt-5.4-mini` but not `gpt-5.4`. Minimal live reasoning probes for mini/low and mini/medium both succeeded and resolved to `gpt-5.4-mini-2026-03-17`. The full-model medium probe returned HTTP 403, `model_not_found`; no substitute model was run.

Reproduce with configured OpenAI credentials:

```bash
docker build \
  --build-arg VCS_REF="$(git rev-parse HEAD)" \
  -t findworks-runtime:evaluation \
  runtime

FINDWORKS_RUNTIME_IMAGE=findworks-runtime:evaluation \
  npm run benchmark:adaptive-interview -- \
  --commit "$(git rev-parse HEAD)" \
  --repeats 3 \
  --output artifacts/adaptive-interview-model-benchmark/current
```

## Aggregate distributions

| Measure | Mini / low | Mini / medium | Medium change |
| --- | ---: | ---: | ---: |
| Scenario runs | 54 | 54 | — |
| Runtime Turns | 259 | 257 | -2 |
| Semantic pass rate, pooled | 88.55% | 89.16% | +0.61 points |
| Semantic pass rate, p25 / p50 across runs | 86.3% / 92.6% | 88.0% / 100.0% | +1.7 / +7.4 points |
| Required coverage, mean | 83.80% | 88.89% | +5.09 points |
| Runs without sufficient Evidence | 11 | 7 | -4 |
| Useful Evidence per Question, mean | 0.567 | 0.617 | +0.050 |
| Useful Evidence per Question, p50 | 0.500 | 0.500 | no change |
| Turns to sufficient Evidence, p50 / p95 | 2 / 19 | 2 / 33 | no change / +14 |
| Turn latency, p50 / p95 | 5.16 s / 6.68 s | 6.46 s / 9.62 s | +25.3% / +43.9% |
| Cost per run, p50 / p95 | $0.004 / $0.091 | $0.005 / $0.138 | +25% / +51.6% |
| Cost per Question, p50 | $0.002 | $0.003 | +50% |
| Total provider-reported cost | $0.776247 | $1.126387 | +45.1% |
| Input / output tokens | 1,216,640 / 68,414 | 1,212,018 / 129,790 | -0.4% / +89.7% |

Provider costs are reported in USD; the relative quality-per-cost result is currency-independent. Median cost per useful Evidence was $0.004 for low and $0.006 for medium, a 1.5× ratio. Semantic checks passed per provider-reported dollar fell from 1,704 to 1,168.

## Failures and safety signals

| Signal | Mini / low | Mini / medium |
| --- | ---: | ---: |
| Runs with deterministic violations | 18 | 19 |
| Required semantic-gate failures | 27 | 23 |
| Continuity-gate failures | 13 | 12 |
| Missed expected Conflicts | 0 | 0 |
| Repeated Questions | 46 | 35 |
| Early completions | 0 | 0 |
| Retained individual failures | 259 | 228 |

The most frequent degradation is repetition and loss of fixture-expected vocabulary or thread progression in the 20- and 40-turn scenarios. Medium reasoning reduced repetitions and incomplete coverage, but still produced deterministic, required semantic, and continuity failures. Individual failures remain in the linked JSON with configuration, scenario, repeat, turn, stable rule, synthetic source answer, and offending action.

The evaluated report predates the subsequently integrated correction that stops sentence-opening transition words such as “Once” from being classified as unsupported names. Those observed failures remain retained rather than rewritten. Post-merge deterministic tests cover that correction.

## Promotion criterion and outcome

A candidate promotes only if it has:

1. zero deterministic, required semantic, continuity, missed-contradiction, repeated-Question, and early-completion failures;
2. at least a five-point improvement in lower-quartile semantic pass rate;
3. no more than a two-point regression in median useful Evidence per Question;
4. no more than 2× median cost per useful Evidence; and
5. no more than 2× p95 latency.

Mini/medium stayed inside the cost, Evidence-yield, and latency ceilings, but its lower-quartile semantic gain was only 1.7 points and it did not clear the zero-failure gates. It is not promoted. `gpt-5.4` medium cannot be assessed until provider access is granted and the same pinned three-trial suite is rerun; that is the sole benchmark blocker.
