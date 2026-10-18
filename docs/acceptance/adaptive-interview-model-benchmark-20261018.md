# Adaptive Interview model benchmark — 2026-10-18

## Outcome

Issue #66 now has one corrected three-repeat comparison across all 18 versioned fixtures. All three manifest configurations completed through the accepted Runtime Turn seam. The benchmark exited nonzero only because it retained genuine behavioural failures.

**Recommendation: retain `openai/gpt-5.4-mini` / low.** Neither candidate meets the predeclared promotion criterion:

- `gpt-5.4` / medium improves lower-quartile semantic quality by only `0.04585267164223772`, below the required `0.05`; costs `5.561920349154392×` as much per useful Evidence; has `3.4170624941943495×` p95 latency; and still has contract, semantic-gate, continuity, repeated-Question, and completion failures.
- mini / medium improves lower-quartile semantic quality by only `0.008025094128051746`; costs `1.5794386858216647×` as much per useful Evidence; has `1.3320005079073873×` p95 latency; and still has contract, semantic-gate, continuity, repeated-Question, completion, and one missed-contradiction failure.

This evidence does not justify production routing or a model switch.

Machine-readable evidence:

- [combined benchmark and individual failures](./traces/issue-63-66-model-benchmark-2082176.json)
- [mini / low complete trace](./traces/issue-63-gpt-5.4-mini-low-2082176.json)
- [GPT-5.4 / medium complete trace](./traces/issue-66-gpt-5.4-medium-2082176.json)
- [mini / medium complete trace](./traces/issue-66-gpt-5.4-mini-medium-2082176.json)

The retained files contain synthetic fixture content and sanitised semantic/runtime records only. They contain no credential, provider payload, project identity, Pi session, or production interview content.

## Reproducibility

- Clean evaluated commit: `208217662a60318f4e65a646361e908f99a2a934`.
- Runtime image: `findworks-runtime:issue-63-66-2082176`.
- Image ID: `sha256:aec034e189b164ddf95e12b2da14a92c69d317d25d2579b1e595894b42957f36`.
- Image source revision: `208217662a60318f4e65a646361e908f99a2a934`.
- Pi runtime: `0.84.4`.
- Manifest: `runtime/evaluation/benchmark-configurations.v1.json` version `1.0.0`.
- Portfolio: all 18 fixture versions recorded in the machine trace.
- Trials: three repeats per configuration, 54 runs each and 162 runs total.
- Execution: exactly one serial foreground benchmark process, from `2026-10-18T10:57:58.436Z` to `2026-10-18T12:33:59.766Z`; no detached, duplicate, or concurrent process and no Runtime Turn execution error.
- Credential: `OPENAI_API_KEY` sourced from `findworks-pilot-worker-1` only into command environments. Provider preflight and Runtime Turns used the same API-key path. It was neither printed nor persisted.

## Provider and per-turn identity verification

A minimal same-credential mini/low preflight returned HTTP 200, not `credit_balance_exhausted`. The benchmark then independently verified every configuration:

| Configuration | Listed request | Reasoning | Alias probe identity | Direct dated request | Observed successful turns |
| --- | --- | --- | --- | --- | ---: |
| mini / low | `gpt-5.4-mini` | low | `gpt-5.4-mini-2026-03-17` | HTTP 403 `model_not_found` | 258 |
| GPT-5.4 / medium | `gpt-5.4` | medium | `gpt-5.4-2026-03-05` | HTTP 403 `model_not_found` | 258 |
| mini / medium | `gpt-5.4-mini` | medium | `gpt-5.4-mini-2026-03-17` | HTTP 403 `model_not_found` | 256 |

Because the dated identities were not directly requestable, Pi received the provider-listed stable alias. Every successful turn reported exactly the expected provider, stable requested/response alias, and configured reasoning effort. Each configuration had one unique observed identity and no missing, mixed, changed, or substituted identity.

## Aggregate distributions

Entries below are `min / p25 / p50 / p75 / p95 / max (mean)` across the 54 runs in each configuration. Full-precision values remain in the machine record.

| Measure | Mini / low | GPT-5.4 / medium | Mini / medium |
| --- | --- | --- | --- |
| Semantic pass rate | .500 / .882 / 1 / 1 / 1 / 1 (.923) | .500 / .928 / 1 / 1 / 1 / 1 (.962) | .733 / .890 / 1 / 1 / 1 / 1 (.948) |
| Required accepted-outcome coverage | 0 / .125 / 1 / 1 / 1 / 1 (.681) | 0 / 0 / 1 / 1 / 1 / 1 (.667) | 0 / .063 / 1 / 1 / 1 / 1 (.718) |
| Useful accepted Evidence / Question | 0 / .013 / .500 / 1 / 1 / 1 (.496) | 0 / 0 / .500 / 1 / 1 / 1 (.534) | 0 / 0 / .500 / 1 / 1 / 1 (.532) |
| Turns to sufficient Evidence | 1 / 1 / 2 / 2 / 2 / 2 (1.515; n=33) | 1 / 1 / 1.5 / 2 / 2.25 / 3 (1.556; n=36) | 1 / 1 / 2 / 2 / 2 / 3 (1.553; n=38) |
| Turn latency | 4,166 / 4,938 / 5,235 / 5,578 / 6,279 / 7,965 ms (5,301) | 5,351 / 7,815 / 9,237 / 11,258 / 21,457 / 51,204 ms (10,659) | 4,540 / 5,563 / 6,251 / 6,987 / 8,364 / 15,674 ms (6,389) |
| Cost / run | $.001530 / $.002170 / $.003699 / $.005430 / $.094762 / $.150735 ($.015031) | $.006923 / $.011823 / $.019634 / $.027088 / $.502138 / $.830168 ($.080420) | $.001913 / $.003837 / $.005594 / $.007851 / $.134451 / $.203210 ($.020783) |
| Cost / Question | $.001530 / $.001806 / $.002062 / $.002475 / $.003673 / $.003768 ($.002272) | $.006923 / $.009185 / $.011469 / $.014448 / $.019252 / $.022358 ($.012194) | $.001913 / $.002752 / $.003257 / $.004228 / $.005009 / $.005600 ($.003423) |

The benchmark's trade-off calculation uses median cost per Question divided by median useful Evidence per Question:

| Configuration | Cost / useful Evidence | Relative cost | p95 latency ratio | Lower-quartile semantic delta | Median Evidence-yield delta |
| --- | ---: | ---: | ---: | ---: | ---: |
| mini / low | $0.00412425 | 1× | 1× | 0 | 0 |
| GPT-5.4 / medium | $0.02293875 | 5.561920349× | 3.417062494× | +0.045852672 | 0 |
| mini / medium | $0.006514 | 1.579438686× | 1.332000508× | +0.008025094 | 0 |

## Failures and total cost

| Configuration | Runs / turns | Contract-failing runs | Required-semantic failures | Continuity failures | Missed contradictions | Repeated-Question turns | Early-completion rule | Other completion errors | Tokens in / out | Total cost |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| mini / low | 54 / 258 | 17 | 23 | 21 | 0 | 43 | 0 | 12 | 1,293,772 / 61,898 | $0.811686 |
| GPT-5.4 / medium | 54 / 258 | 10 | 10 | 18 | 0 | 43 | 0 | 9 | 1,355,772 / 161,395 | $4.342707 |
| mini / medium | 54 / 256 | 12 | 19 | 16 | 1 | 30 | 0 | 8 | 1,283,311 / 115,383 | $1.122283 |

Total provider-reported fixture cost was `$6.27667575`. Minimal capability probes are not included because the Responses API records retained by this harness do not expose their billed cost.

`early-completion rule` counts only server-contract `premature-completion`. `Other completion errors` also includes fixture `expected-action` failures, such as proposing completion before the fixture's next required answer; those valid failures remain in the traces even though the contract considered the submitted outcomes terminal.

Individual failures are too numerous to repeat here without obscuring the distributions. The combined trace retains every sanitised source answer, offending action, rule, fixture, repeat, and turn. The dominant long-context failures were repeated information needs, switching results without accepted provenance, incomplete expected outcomes, loss of interviewee vocabulary, incomplete traceability, and inconsistent compaction retention.

## Promotion decision and baseline

A candidate promotes only with zero deterministic, required-semantic, continuity, missed-contradiction, repeated-Question, and early-completion failures; at least a `0.05` lower-quartile semantic gain; no more than a `0.02` median Evidence-yield regression; no more than a `2.0` cost-per-useful-Evidence ratio; and no more than a `2.0` p95-latency ratio.

Neither candidate qualifies. Retain mini/low as the explicit default and prioritise interview strategy/provenance/continuity improvements over spending more on the model. The compatible baseline at `runtime/evaluation/baselines/gpt-5.4-mini-low.json` now derives from this corrected accepted-outcome run and records its failures as the regression floor rather than presenting it as a passing quality claim.

## Blockers

There is no provider-access, credential, model-identity, runtime-execution, or evidence-integrity blocker. The remaining blockers are behavioural quality failures in all configurations; they block promotion, not retention or publication of the evidence.
