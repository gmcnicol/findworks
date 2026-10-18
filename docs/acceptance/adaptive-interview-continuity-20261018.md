# Adaptive Interview continuity evaluation — 2026-10-18

## Outcome

Issue #63 now has a corrected credentialed run. `openai/gpt-5.4-mini` at low reasoning completed three serial trials of every versioned fixture through the accepted Runtime Turn seam. The two continuity fixtures exposed material behavioural failures rather than a transport or evaluation blocker:

- the 20-turn fixture proposed completion after turn 19 in all three trials, so the required twentieth answer was never consumed;
- the 40-turn fixture completed all turns, but no trial made every investigated item traceable and one trial lost critical Evidence representation after compaction;
- repeated information needs remained frequent; and
- no fabricated fact, Mission-planning leak, or ungrounded choice was detected in either continuity fixture.

The valid failing evaluations are retained. Issue #63's quality acceptance is not met, but its corrected metrics and regression evidence are now established.

Machine-readable evidence:

- [mini/low full trace](./traces/issue-63-gpt-5.4-mini-low-2082176.json)
- [combined benchmark record](./traces/issue-63-66-model-benchmark-2082176.json)
- [model comparison](./adaptive-interview-model-benchmark-20261018.md)

## Reproducibility and identity

- Clean evaluated commit: `208217662a60318f4e65a646361e908f99a2a934`.
- Runtime image: `findworks-runtime:issue-63-66-2082176`.
- Image ID: `sha256:aec034e189b164ddf95e12b2da14a92c69d317d25d2579b1e595894b42957f36`.
- Image source revision: `208217662a60318f4e65a646361e908f99a2a934`.
- Pi runtime: `0.84.4`.
- Configuration: provider-listed `openai/gpt-5.4-mini`, low reasoning.
- Observed Runtime Turn identity: `openai` / requested `gpt-5.4-mini` / response `gpt-5.4-mini` / reasoning `low` on all 258 successful turns.
- Provider preflight: alias request HTTP 200, response identity `gpt-5.4-mini-2026-03-17`; direct dated request HTTP 403 `model_not_found`. The stable listed alias was therefore the enforced runtime identity.
- Portfolio: all 18 fixture versions, three repeats, 54 runs.
- Execution: one serial foreground benchmark process; no execution errors, detached process, duplicate, or concurrency.
- Credential: the same `OPENAI_API_KEY` path was sourced from `findworks-pilot-worker-1` only into command environments for preflight and turns. It was not printed, logged, or persisted.

## Corrected mini/low continuity trials

### 20-turn claims routing

| Repeat | Accepted turns | Required coverage | Useful Evidence / Question | Repeated Questions | Traceable items | Critical Evidence retained | Latency p50 / p95 | Input / output tokens | Cost |
| ---: | ---: | ---: | ---: | ---: | --- | --- | --- | ---: | ---: |
| 1 | 19 | 0% | 0.000 | 6 | no | no | 5,376.9 / 5,857.6 ms | 103,742 / 4,912 | $0.067079 |
| 2 | 19 | 0% | 0.000 | 4 | no | no | 5,196.3 / 6,442.8 ms | 103,945 / 4,266 | $0.064324 |
| 3 | 19 | 0% | 0.000 | 5 | no | no | 5,123.2 / 5,981.5 ms | 103,366 / 4,403 | $0.064506 |

Every trial proposed completion when the fixture expected one final Question. The harness retained `expected-action` failures and stopped immediately, as the live runtime would. The missing twentieth Evidence item therefore also failed critical-Evidence retention and left both investigated items untraceable under the corrected accepted-outcome scoring.

Across trials, repeated-Question count was min/p25/p50/p75/p95/max `4 / 4.5 / 5 / 5.5 / 5.9 / 6`. Median required coverage and useful Evidence per Question were both zero. Median per-trial p50/p95 latency was `5,196.3 / 5,981.5 ms`; median trial cost was `$0.064506`. No trial reached sufficient accepted Evidence for every required item, so turns-to-sufficiency is unavailable.

Early/middle/late semantic pass rates were respectively `88.1/84.6/90.0%`, `90.5/83.8/90.0%`, and `90.5/86.5/83.3%`. Late-minus-early change ranged from `-7.14` to `+1.90` percentage points (median `-0.48`). The dominant late failure was failure to use the interviewee's vocabulary.

### 40-turn permit handover with compaction

| Repeat | Accepted turns | Required coverage | Useful Evidence / Question | Repeated Questions | Traceable items | Critical Evidence retained | Latency p50 / p95 | Input / output tokens | Cost |
| ---: | ---: | ---: | ---: | ---: | --- | --- | --- | ---: | ---: |
| 1 | 40 | 75% | 0.075 | 10 | no | yes | 5,342.6 / 6,440.3 ms | 232,738 / 9,683 | $0.150735 |
| 2 | 40 | 0% | 0.000 | 10 | no | no | 5,145.7 / 6,284.4 ms | 231,780 / 9,682 | $0.148284 |
| 3 | 40 | 50% | 0.050 | 8 | no | yes | 5,059.3 / 6,046.3 ms | 229,441 / 9,603 | $0.146174 |

All trials dropped 28 old Question/Evidence records from the visible window. Durable `current_outcomes` retained every declared critical Evidence item in repeats 1 and 3, but repeat 2 lost critical representation and retained no qualifying accepted outcome. No trial made all four Investigation Results traceable.

Required coverage was min/p25/p50/p75/p95/max `0 / 0.25 / 0.50 / 0.625 / 0.725 / 0.75`; useful Evidence per Question was `0 / 0.025 / 0.050 / 0.0625 / 0.0725 / 0.075`. Repeated-Question count was `8 / 9 / 10 / 10 / 10 / 10`. Median per-trial p50/p95 latency was `5,145.7 / 6,284.4 ms`; median trial cost was `$0.148284`. No trial reached sufficient accepted Evidence for every required item.

Early/middle/late semantic pass rates were `88.5/90.1/88.5%`, `87.1/88.6/83.8%`, and `88.5/91.4/85.9%`. Late-minus-early change ranged from `-3.28` to `-0.04` percentage points (median `-2.61`). Vocabulary grounding and missing expected Evidence provenance remained the late failures.

## Aggregate continuity finding

The six mini/low continuity trials consumed 1,005,012 input tokens and 42,549 output tokens at a provider-reported cost of `$0.6411015`. They produced 177 accepted Runtime Turns, no runtime execution error, no fabricated-fact failure, no Mission-planning-language leak, and no ungrounded-choice failure. They nevertheless failed every continuity gate because coverage/provenance did not keep pace with the scripted conversation, repeated Questions accumulated, and critical Evidence was not consistently represented after completion or compaction.

The checked-in mini/low baseline now points to this corrected compatible run. It is a behavioural floor, not a passing-quality claim.
