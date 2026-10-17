# Conversational Interview UI acceptance evidence

Date: 2026-10-17  
Issue: #68  
Branch: `ticket/68-conversational-interview-ui`

## Delivered behavior

- The Interviewee sees a chronological, durable Question/Evidence transcript and exactly one composer. Choosing a saved response enters a server-addressed revision mode that suppresses the current-answer composer until correction is cancelled or submitted.
- A correction appends immutable `REVISION` Evidence linked through `revises`; it does not overwrite the accepted Evidence. The long-session fixture and captures now contain a real original-plus-`REVISION` pair rather than correction-like answer copy.
- Active, waiting, paused, runtime-failed, confirmation, completed, ended-early, stale-revision, offline, and unavailable states have explicit recovery or outcome copy. Long-session lifecycle status and actions precede the transcript, and mutation controls are disabled offline.
- Draft text survives reload and offline transitions in local browser storage. Submission controls are unavailable offline and native HTML form posts remain the baseline behavior.
- Clerk-backed saved-work continuity and existing Interview Session routes remain intact.
- The interaction uses the existing Datastar semantic refresh path. A single focused heading announces the current Question or lifecycle state; the duplicate live-region path and repeated complementary landmarks are absent. It adds no realtime transport, fake token streaming, or simulated typing.
- Small interview metadata, progress, hint, and placeholder text meet the automated 4.5:1 contrast floor. Progress copy follows persisted Investigation Result state (for example, `Exploring 1 key area`) rather than presenting terminal outcomes as conversational coverage.

## Automated evidence

- JVM suite: 95 tests passed in the `mvn clean test` phase of `bash scripts/test.sh`.
- Browser suite: `FINDWORKS_E2E_PORT=8168 npm test` — 31 tests passed.
- The bundled browser phase of `bash scripts/test.sh` could not bind its default port because local port 8080 was already in use; the complete browser suite was rerun successfully on isolated port 8168.
- Browser coverage includes a realistic 21-answered-turn/22-question session with an appended revision, exclusive answer/revision composers, fresh mobile landing focus without manual scrolling, long paused/runtime-failed/confirmation/completed states, lifecycle offline protection, no repeated complementary landmarks or duplicate Question announcer, computed small-text contrast, narrow and wide overflow checks, draft recovery, correction history, and Clerk continuity.
- JVM coverage verifies the server-rendered exclusive revision mode, original-plus-appended Evidence, `expectedRevision` stale protection, and progress language across unaddressed, exploring, and terminal Investigation Result states.

## Visual evidence

One confirmation round rebuilt the visual stack and recaptured both viewports from the same realistic 21-turn session:

- Mobile, 390 × 844 viewport: [`.impeccable/review/mobile.png`](../../.impeccable/review/mobile.png)
- Widescreen, 1440 × 1000 viewport: [`.impeccable/review/desktop.png`](../../.impeccable/review/desktop.png)

The full-page captures show readable chronological scanning, a stable 700px widescreen measure, no narrow-screen horizontal overflow, a visible appended correction with `Correction · saved` metadata, authoritative `Exploring 1 key area` progress, and one composer at the end of history. The confirmation assertions also checked one visible textarea, the actual revision marker, and no horizontal overflow at both viewports. No second capture round was needed.

## Mechanical detector

The Impeccable detector ran once during the original UI completion. It was deliberately not rerun for this bounded finish-review fix batch, as requested. Its original result reported 140 findings: 4 warnings and 136 design-system advisories.

The warnings were reviewed as non-blocking:

- one pre-existing side-border treatment in the Investigator findings review, outside this Interviewee surface;
- the existing global Inter declaration;
- two flat-type-hierarchy false positives from scanning Thymeleaf templates/fragments without their resolved stylesheet. The batched rendered inspection shows the actual serif Question hierarchy.

Most advisories identify literal colors/type steps already present across the shared stylesheet. `DESIGN.md` now records the Interviewee warm-paper palette, durable transcript layout, editorial Question type, Evidence cards, composer, progress, and lifecycle rules. The raw original detector result was retained at `/tmp/issue68-impeccable-detector.json` for that run. This fix batch adds automated DOM, focus, offline, ordering, and contrast checks; it does not claim a new NVDA or VoiceOver transcript.

## Integration

`integration/adaptive-interview-evaluation` was merged after completion; Git reported the ticket branch was already up to date.
