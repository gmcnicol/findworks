# Adaptive Interview continuity evaluation — 2026-10-18

## Outcome

Issue #63 remains externally blocked. The final corrected `openai/gpt-5.4-mini` / low gate stopped at same-credential provider preflight with HTTP 429 `credit_balance_exhausted`, before any of the 18 versioned fixtures ran. There is therefore no fresh accepted-outcome evidence for the 20-turn or 40-turn continuity fixtures.

Evidence is shared with the issue #66 gate:

- [final corrected machine trace](./traces/issue-63-66-corrected-gate-18a2aaf.json)
- [catalogue and identity observations](./traces/issue-63-66-model-access-18a2aaf.json)
- [readable benchmark record](./adaptive-interview-model-benchmark-20261018.md)

## Requested run

- Clean evaluated commit: `18a2aafaeb3a50f2681999d3c5a11437cf28fb98`.
- Runtime: Pi `0.84.4` in image `findworks-runtime:issue-63-66-18a2aaf` (`sha256:b376b123dc6b302fb2e6b7f9e0de54c7a8e56e7e5dff60548a0e33b44905ff7c`).
- Configuration: provider-listed `openai/gpt-5.4-mini`, low reasoning, with the stable alias required on every successful Runtime Turn.
- Portfolio: all 18 versioned fixtures, including `claims-routing-20-turn-continuity@1.0.0` and `permit-handover-40-turn-compaction@1.0.0`.
- Repeats: three requested.
- Credential: the same `OPENAI_API_KEY` path for preflight and turns; no secret retained.
- Execution: serial and foreground; no concurrent, detached, or duplicate process.

## Corrected continuity metrics

No Runtime Turn was executed in the retained gate. These values are unavailable rather than zero:

| Measure | 20-turn fixture | 40-turn fixture |
| --- | --- | --- |
| Completed trials | 0 | 0 |
| Accepted Runtime Turns | unavailable | unavailable |
| Required accepted-outcome coverage | unavailable | unavailable |
| Useful accepted Evidence / Question | unavailable | unavailable |
| Turns to sufficient accepted Evidence | unavailable | unavailable |
| Repeated Questions | unavailable | unavailable |
| Fabricated facts / planning leaks / ungrounded choices | unavailable | unavailable |
| Traceable investigated items | unavailable | unavailable |
| Critical Evidence retained after compaction | unavailable | unavailable |
| Token use and provider-reported cost | unavailable | unavailable |
| Turn latency p50 / p95 | unavailable | unavailable |
| Early / middle / late semantic distribution | unavailable | unavailable |

No full-precision calculation exists because there are no accepted observations.

## Continuity finding

The 20-turn and 40-turn regression fixtures remain versioned and unchanged, but this gate cannot establish whether quality degrades with context growth. The historical 2026-10-17 traces still show repeated information needs, transition/provenance failures, incomplete required coverage, and compaction-related loss; they remain regression examples only because they predate corrected accepted-outcome and actual-turn identity gates.

Issue #63's published-metrics acceptance remains blocked until the API-key project can fund three complete mini/low repeats. The checked-in baseline is unchanged because blocked evidence is not compatible replacement evidence.
