# Adaptive Interview continuity evaluation — 2026-10-17

## Run

- Commit: `c6197f6a0860471c0e113078d6488d9c3f886ba6`
- Runtime: Pi `0.84.4`
- Configured model: `openai/gpt-5.4-mini`
- Configured reasoning effort: `low`
- Fixtures: `claims-routing-20-turn-continuity@1.0.0`, `permit-handover-40-turn-compaction@1.0.0`
- Synthetic Runtime Turns: 60
- Input/output tokens: 300,658 / 19,440
- Provider-reported cost: $0.310209
- Turn latency: p50 5,663.7 ms; p95 8,020.8 ms
- Full sanitised synthetic trace: [`traces/issue-63-gpt-5.4-mini-low-c6197f6.json`](./traces/issue-63-gpt-5.4-mini-low-c6197f6.json)
- Readable trace: [`traces/issue-63-gpt-5.4-mini-low-c6197f6.md`](./traces/issue-63-gpt-5.4-mini-low-c6197f6.md)

No production Interview Session, credential, provider payload, or Pi session file is retained.

**Historical-evidence correction (no rerun):** this run predates per-turn provider/request/response identity capture, so the configured model above is not proof of the actual model snapshot used on all 60 turns. It also predates the corrected accepted-outcome metric gate. Required-coverage and Evidence-yield values below remain historical observations and do not establish #63's corrected quantitative acceptance. The retained failures and synthetic traces remain regression evidence; a fresh credentialed run is required for corrected model-identity and outcome metrics.

## Result

The quality floor did **not** pass. Assertions were retained unchanged.

| Scenario | Contract | Semantic | Required coverage | Evidence-linked answers | Continuity |
| --- | ---: | ---: | ---: | ---: | ---: |
| 20-turn claims routing | 9 violations | 98/114 (86.0%) | 100% | 0.350/question | Fail |
| 40-turn permit handover | 26 violations | 211/243 (86.8%) | 75% | 0.425/question | Fail |

Across both scenarios, no configured fabricated fact, private Mission-planning term, or ungrounded choice appeared. The failures were repeated information needs, premature or late thread transitions, incomplete outcome provenance, and loss of required coverage.

## Context-growth findings

### 20-turn Mission

Semantic quality declined from 88.1% in the early third to 81.5% in the late third, a 6.6-point drop. The agent moved from the first result before a later correction arrived, repeated the next thread's approval question, repeated the rescue-log question after a revised authority limit, and proposed completion one answer before the fixture's explicit final recap. Both results became terminal, but the resulting supported outcomes did not trace all expected supporting Evidence.

### 40-turn Mission with compaction

The visible Question/Evidence window dropped 28 older records while durable outcomes remained. Semantic quality was 85.9% early, 89.9% middle, and 84.8% late. After the third stage, the agent continued asking school/bus consultation questions while the interviewee supplied the crew-handover sequence. It repeatedly revisited that ground, never made the fourth result terminal, and ended at 75% required coverage. Critical Evidence from earlier compacted stages was not completely preserved through outcome provenance.

The degradation is therefore not a simple token-limit failure: it includes transition timing before compaction, while bounded-history loss amplifies the late-stage failure. The two versioned fixtures retain these regressions for comparison with runtime/skill changes.
