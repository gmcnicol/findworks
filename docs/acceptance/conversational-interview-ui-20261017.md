# Conversational Interview UI acceptance evidence

Date: 2026-10-17  
Issue: #68  
Branch: `ticket/68-conversational-interview-ui`

## Delivered behavior

- The Interviewee sees a chronological, durable Question/Evidence transcript and one current composer.
- A correction appends immutable `REVISION` Evidence linked through `revises`; it does not overwrite the accepted Evidence.
- Active, waiting, paused, runtime-failed, confirmation, completed, ended-early, stale-revision, offline, and unavailable states have explicit recovery or outcome copy.
- Draft text survives reload and offline transitions in local browser storage. Submission controls are unavailable offline and native HTML form posts remain the baseline behavior.
- Clerk-backed saved-work continuity and existing Interview Session routes remain intact.
- The interaction uses the existing Datastar semantic refresh path. It adds no realtime transport, fake token streaming, or simulated typing.

## Automated evidence

- JVM suite: `source scripts/use-java-25.sh && mvn test -q`
- Browser suite: `FINDWORKS_E2E_PORT=8168 npm test`
- Browser coverage includes a realistic 21-answer/22-question session, one-composer assertions, narrow and wide overflow checks, draft recovery, disconnection, focus transfer to a real next Question, correction history, lifecycle recovery, and Clerk continuity.

## Visual evidence

One batched inspection used the same realistic 21-turn session:

- Mobile, 390 × 844 viewport: [`.impeccable/review/mobile.png`](../../.impeccable/review/mobile.png)
- Widescreen, 1440 × 1000 viewport: [`.impeccable/review/desktop.png`](../../.impeccable/review/desktop.png)

The full-page captures show readable chronological scanning, a stable 700px widescreen measure, no narrow-screen horizontal overflow, corrections kept in context, and one visually dominant composer at the end of history. No blocking visual finding required a second capture round.

## Mechanical detector

The Impeccable detector ran once after UI completion. It reported 140 findings: 4 warnings and 136 design-system advisories.

The warnings were reviewed as non-blocking:

- one pre-existing side-border treatment in the Investigator findings review, outside this Interviewee surface;
- the existing global Inter declaration;
- two flat-type-hierarchy false positives from scanning Thymeleaf templates/fragments without their resolved stylesheet. The batched rendered inspection shows the actual serif Question hierarchy.

Most advisories identify literal colors/type steps already present across the shared stylesheet. `DESIGN.md` now records the Interviewee warm-paper palette, durable transcript layout, editorial Question type, Evidence cards, composer, progress, and lifecycle rules. The raw detector result was retained at `/tmp/issue68-impeccable-detector.json` for this run.

## Integration

`integration/adaptive-interview-evaluation` was merged after completion; Git reported the ticket branch was already up to date.
