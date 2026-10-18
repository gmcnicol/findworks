# Adaptive Interview model benchmark — 2026-10-18

## Outcome

The corrected benchmark remains **blocked**. The configured OpenAI project listed every required GPT-5.4 alias and initially accepted the required model/reasoning probes, but its Responses API credit balance was exhausted before the final retained gate. No fixture trial ran, so issue #66 still has no compatible accepted-outcome comparison.

Retain `openai/gpt-5.4-mini` / low only as a conservative no-change decision. This run does not validate its quality or cost and does not justify promotion, routing, or a production model change.

Machine-readable evidence:

- [final corrected gate](./traces/issue-63-66-corrected-gate-18a2aaf.json)
- [catalogue, identity, and credential-path observations](./traces/issue-63-66-model-access-18a2aaf.json)

No credential, provider payload, project identity, Pi session, or production interview content is retained.

## Reproducibility

- Evaluated clean commit: `18a2aafaeb3a50f2681999d3c5a11437cf28fb98`.
- Runtime image: `findworks-runtime:issue-63-66-18a2aaf`.
- Image ID: `sha256:b376b123dc6b302fb2e6b7f9e0de54c7a8e56e7e5dff60548a0e33b44905ff7c`.
- Image source revision: `18a2aafaeb3a50f2681999d3c5a11437cf28fb98`.
- Pi runtime: `0.84.4`.
- Manifest: `runtime/evaluation/benchmark-configurations.v1.json` version `1.0.0`.
- Portfolio: all 18 fixture versions recorded in the final machine trace.
- Requested trials: three repeats per accessible configuration.
- Execution: one final corrected serial foreground benchmark process. Two earlier serial attempts stopped during preflight/first-turn identity validation and exposed defects in handling provider-listed stable aliases; each defect was reproduced with a failing test, fixed, committed, and rebuilt before the final corrected gate. No process was detached, duplicated, or run concurrently.
- Credential: `OPENAI_API_KEY` sourced from `findworks-pilot-worker-1` into the command environment. The same API-key path was selected for provider preflight and Runtime Turns. It was neither printed nor persisted.
- GPT-5.6 was inspected only as catalogue/configuration plumbing and was not added to the versioned benchmark.

## Provider and identity verification

The host Pi 1.3.0 catalogue used an `openai-codex` OAuth path and exposed the three configured GPT-5.6 variants, but no GPT-5.4 model. The benchmark therefore continued to use its explicit `openai` API-key path. Pi 0.84.4 in the runtime image exposed `gpt-5.4`, `gpt-5.4-mini`, `gpt-5.4-nano`, and `gpt-5.4-pro`. The same API key's provider catalogue listed the required `gpt-5.4` and `gpt-5.4-mini` aliases.

Before credit exhaustion, minimal same-credential probes established:

| Configuration | Provider-listed request identity | Reasoning | Responses API identity | Direct dated identity request |
| --- | --- | --- | --- | --- |
| `gpt-5.4-mini-low` | `gpt-5.4-mini` | low | `gpt-5.4-mini-2026-03-17` | HTTP 403 `model_not_found` |
| `gpt-5.4-medium` | `gpt-5.4` | medium | `gpt-5.4-2026-03-05` | HTTP 403 `model_not_found` |
| `gpt-5.4-mini-medium` | `gpt-5.4-mini` | medium | `gpt-5.4-mini-2026-03-17` | HTTP 403 `model_not_found` |

Pi's first successful diagnostic turn for each configuration reported only the stable provider-listed alias as both requested and response identity. The harness now treats that as the strongest enforceable per-turn identity when the dated Responses API identity is not directly requestable. It requires the exact provider, stable alias, and reasoning effort on every successful turn; a missing, mixed, or substituted identity still invalidates the evaluation. The dated probe response remains retained separately and is not fabricated as Pi's observed turn identity.

At the final corrected gate, all three alias probes returned HTTP 429 `credit_balance_exhausted`. Therefore every configuration was blocked before fixture execution:

| Configuration | Required | Final status | Scenario trials | Successful Runtime Turns |
| --- | --- | --- | ---: | ---: |
| `gpt-5.4-mini-low` | yes | blocked | 0 | 0 |
| `gpt-5.4-medium` | yes | blocked | 0 | 0 |
| `gpt-5.4-mini-medium` | no | blocked | 0 | 0 |

## Accepted-outcome metrics and full-precision calculations

There were no server-accepted Runtime Turn outcomes in the retained gate. The following are unavailable, not measured zero:

| Measure | Mini / low | GPT-5.4 / medium | Mini / medium |
| --- | --- | --- | --- |
| Semantic pass distribution | unavailable | unavailable | unavailable |
| Required accepted-outcome coverage distribution | unavailable | unavailable | unavailable |
| Useful accepted Evidence / Question distribution | unavailable | unavailable | unavailable |
| Turns to sufficient accepted Evidence distribution | unavailable | unavailable | unavailable |
| Contradictions missed / repetitions / early completions | unavailable | unavailable | unavailable |
| Turn-latency distribution | unavailable | unavailable | unavailable |
| Provider-reported tokens and cost | unavailable | unavailable | unavailable |
| Cost / Question | unavailable | unavailable | unavailable |
| Cost / useful Evidence | unavailable | unavailable | unavailable |

Consequently there are no percentile, delta, ratio, or cost/quality calculations to round or publish. The machine trace retains `null` summaries and blocked statuses rather than numeric zeroes or estimates. Minimal probes did not provide usage or cost data, and no cost is inferred for them.

## Promotion criterion and recommendation

A candidate promotes only with:

1. zero deterministic, required-semantic, continuity, missed-contradiction, repeated-Question, and early-completion failures;
2. at least a 0.05 lower-quartile semantic pass-rate gain;
3. no more than a 0.02 median useful-Evidence-per-Question regression;
4. no more than a 2.0 full-precision median cost-per-useful-Evidence ratio; and
5. no more than a 2.0 full-precision p95-latency ratio.

No candidate was eligible to evaluate. Retain mini/low without claiming validation. The promotion decision remains blocked until the same API-key path has sufficient Responses API credit for both required configurations to complete all 18 fixture versions three times through the accepted Runtime Turn seam.

## Baseline

`runtime/evaluation/baselines/gpt-5.4-mini-low.json` is unchanged. This gate has no compatible completed mini/low evaluation and therefore cannot replace the historical baseline. Its corrected accepted-outcome metrics, continuity findings, actual-turn identities, and cost distributions remain unestablished.
