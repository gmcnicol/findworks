# Adaptive Interview model benchmark — 2026-10-17

## Decision

Retain `openai/gpt-5.4-mini` with low reasoning as the default, but treat this only as a conservative no-change decision. The fresh corrected credentialed gate produced no model-backed interview trials because neither required configuration passed exact-model preflight. It does not establish that mini/low is better, cheaper, or acceptable.

Do not add production model routing and do not promote a candidate. Rerun the same pinned gate only after the configured OpenAI project can request both exact provider-resolved required models.

The sanitised machine-readable evidence is [`issue-62-66-credentialed-gate-8579e0c.json`](./traces/issue-62-66-credentialed-gate-8579e0c.json). The earlier benchmark remains archived as [`issue-66-model-benchmark-6a66aeb.json`](./traces/issue-66-model-benchmark-6a66aeb.json), with its affected outcome metrics explicitly invalidated.

## Fresh corrected gate

- Evaluated clean commit: `8579e0c46569b1c3a36fd4833386544bb2b664da`.
- Runtime: Pi `0.84.4`.
- Runtime image: `findworks-runtime:credentialed-rerun-8579e0c`.
- Image ID: `sha256:61c4519566d248ab57095226bf86be281583d7207ef9a2dd5a4b6d0bb6e1ca5b`.
- Configuration manifest: `runtime/evaluation/benchmark-configurations.v1.json` version `1.0.0`.
- Fixture portfolio: all 18 versioned synthetic scenarios in `runtime/evaluation/fixtures/v1` (exact versions are retained in the machine record).
- Requested repeats: three per accessible configuration.
- Credential path: `OPENAI_API_KEY`, obtained from `findworks-pilot-worker-1` only inside command environments and passed to both preflight and Pi through the same API-key path. It was not printed, logged, written to evidence, or persisted.
- Execution: serial foreground benchmark processes only; no detached, background, duplicate, or concurrent process was used. Earlier attempts stopped when they exposed exact-model and blocked-evidence harness defects. After each defect was test-driven, committed, and rebuilt, only the rerun required to restore evidence integrity was performed. The retained attempt is the final corrected gate.

## Exact configurations and trials

| Configuration | Required | Manifest model | Reasoning | Alias listed | Alias resolved to | Exact resolved-model probe | Fixture trials |
| --- | --- | --- | --- | --- | --- | --- | ---: |
| `gpt-5.4-mini-low` | yes | `gpt-5.4-mini` | low | yes | `gpt-5.4-mini-2026-03-17` | blocked: HTTP 403 `model_not_found` | 0 |
| `gpt-5.4-medium` | yes | `gpt-5.4` | medium | no | unavailable | blocked: HTTP 403 `model_not_found` | 0 |
| `gpt-5.4-mini-medium` | no | `gpt-5.4-mini` | medium | yes | `gpt-5.4-mini-2026-03-17` | blocked: HTTP 403 `model_not_found` | 0 |

The manifest alias probe and exact resolved-model probe used the same credential. The harness did not run Pi against an alias after the exact snapshot failed, and it did not substitute another model. The required full-model configuration was recorded as blocked as required.

## Corrected accepted-outcome metrics and aggregate distributions

No Runtime Turn was executed in the retained gate, so there were no server-accepted outcomes. These measures are **unavailable**, not measured zero:

| Measure | Mini / low | GPT-5.4 / medium | Mini / medium |
| --- | ---: | ---: | ---: |
| Scenario trials | 0 (blocked) | 0 (blocked) | 0 (blocked) |
| Runtime Turns | unavailable | unavailable | unavailable |
| Semantic pass distribution | unavailable | unavailable | unavailable |
| Required accepted-outcome coverage distribution | unavailable | unavailable | unavailable |
| Useful accepted Evidence / Question distribution | unavailable | unavailable | unavailable |
| Turns to sufficient accepted Evidence distribution | unavailable | unavailable | unavailable |
| Contradictions missed / repetitions / early completions | unavailable | unavailable | unavailable |
| Latency distribution | unavailable | unavailable | unavailable |
| Provider-reported evaluation tokens and cost | unavailable | unavailable | unavailable |
| Cost / Question and cost / useful Evidence | unavailable | unavailable | unavailable |

There are consequently no unrounded percentile, delta, or ratio calculations to publish. The machine record uses blocked statuses and absent summaries rather than fabricated numeric values. Preflight requests were made, but this harness does not receive provider-reported token usage or cost for them; no preflight cost is estimated.

## Cost/quality trade-off

No cost/quality comparison is possible. The corrected harness did not accept an outcome, receive Runtime Turn usage, or produce a quality distribution for any configuration. Historical totals from the 2026-10-17 archived run remain observations of that older run only and cannot repair this gate.

## Promotion criterion and recommendation

A candidate promotes only if it has:

1. zero deterministic, required semantic, continuity, missed-contradiction, repeated-Question, and early-completion failures;
2. at least a five-point improvement in lower-quartile semantic pass rate;
3. no more than a two-point regression in median useful Evidence per Question;
4. no more than 2× median cost per useful Evidence; and
5. no more than 2× p95 latency.

No candidate was eligible to evaluate against those gates. The recommendation is therefore **retain mini/low without claiming validation**. Promotion remains blocked until both required exact configurations complete all 18 fixture versions for three trials through the corrected Runtime Turn seam.

## Historical evidence status

The archived `6a66aeb` run used three repeats and reported quality/cost observations for mini/low and mini/medium while full/medium was blocked. It predates production-equivalent Runtime Turn rejection, accepted-outcome qualification, exact actual-turn model pinning, and full-precision cost ratios. Its required coverage, useful Evidence, turns-to-sufficiency, per-Question cost percentiles, and derived ratios remain invalid for promotion. The fresh gate did not republish or supersede them.
