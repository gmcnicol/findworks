# Animation improvement plans

Plans were written against commit `85484df`. Execute them serially because every plan may touch `src/main/resources/static/assets/app.css`.

| Plan | Title | Severity | Status | Dependencies |
|---|---|---:|---|---|
| [001](001-hold-to-confirm-deletion.md) | Require a deliberate hold for destructive deletion | HIGH | DONE | None |
| [002](002-bridge-next-question-arrival.md) | Bridge the arrival of each adaptive question | MEDIUM | DONE | Reuse the `--ease-out` token from 001 when present |
| [003](003-animate-copy-confirmation.md) | Animate invitation copy confirmation | LOW | DONE | Reuse the `--ease-out` token from 001 when present |
| [004](004-add-completion-arrival.md) | Give interview completion a restrained arrival | LOW | DONE | Reuse the `--ease-out` token from 001 when present |
| [005](005-animate-findings-review-state.md) | Animate the finding that just changed state | MEDIUM | TODO | Preserve the Datastar fragment contract |
| [006](006-bridge-findings-package-decision.md) | Bridge the final findings-package decision | MEDIUM | TODO | Execute after 005; both touch findings review and its controller |
| [007](007-add-invitation-result-arrival.md) | Give the invitation-link result a restrained arrival | LOW | TODO | Preserve completed copy-feedback plan 003 |
| [008](008-animate-working-state-once-per-turn.md) | Animate the interview working state once per turn | LOW | TODO | Requires Datastar SSE delivery; preserve plan 002 |

## Recommended execution order

Completed foundation:

1. **001 — Hold-to-confirm deletion.** Established deliberate destructive feedback and the shared strong ease-out token.
2. **002 — Question arrival.** Bridges each ready question while keeping repeated working refreshes still.
3. **003 — Copy confirmation.** Adds restrained feedback after clipboard success or failure.
4. **004 — Completion arrival.** Adds a restrained terminal Interview entrance.

Next execution wave:

1. **005 — Finding review state.** Highest-frequency gap; establishes request-scoped animation targeting without moving the page.
2. **006 — Package decision.** Adds a rare View Transition after 005 has settled the shared findings controller and template seams.
3. **007 — Invitation result arrival.** Independent CSS-only entrance that must leave plan 003 unchanged.
4. **008 — Working state once per turn.** Execute last, after the Datastar SSE prerequisite; event-driven delivery removes the old replay risk without client-side storage.

Do not execute plans in parallel. Plans 005 and 006 both modify `FindingsController`, `findings-review.html`, `app.css`, and findings E2E coverage. Plans 007 and 008 touch separate surfaces but must preserve completed plans 003 and 002 respectively.
