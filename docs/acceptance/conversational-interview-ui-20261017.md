# Conversational Interview UI acceptance evidence

Date: 2026-10-17  
Issue: #68  
Branch: `review/web-corrections` (final-review corrections for `ticket/68-conversational-interview-ui`)

## Delivered behavior

- The Interviewee sees a chronological, durable Question/Evidence transcript and exactly one composer. Choosing a saved response enters a server-addressed revision mode that suppresses the current-answer composer until correction is cancelled or submitted.
- A correction appends immutable `REVISION` Evidence linked through `revises`; it does not overwrite the accepted Evidence. The long-session fixture and captures now contain a real original-plus-`REVISION` pair rather than correction-like answer copy.
- Active, waiting, paused, runtime-failed, confirmation, completed, ended-early, stale-revision, offline, and unavailable states have explicit recovery or outcome copy. Long-session lifecycle status and actions precede the transcript, and mutation controls are disabled offline.
- Draft text survives reload, same-tab navigation, and offline transitions in bounded `sessionStorage`. Each 24-hour payload is namespaced by the authoritative Interview Session and Question, capped at 32 drafts of 10,000 characters, isolated from other Sessions, and cleared after acknowledged submission, completion, authorised unavailability, revoked access, and successful sign-out. Legacy origin-wide `localStorage` draft keys are removed. Submission controls are unavailable offline and native HTML form posts remain the baseline behavior.
- Clerk-backed saved-work continuity and existing Interview Session routes remain intact.
- The interaction uses the existing Datastar semantic refresh path. A single focused heading announces the current Question or lifecycle state; the duplicate live-region path and repeated complementary landmarks are absent. It adds no realtime transport, fake token streaming, or simulated typing.
- Small interview metadata, progress, hint, and placeholder text meet the automated 4.5:1 contrast floor. Progress copy follows persisted Investigation Result state (for example, `Exploring 1 key area`) rather than presenting terminal outcomes as conversational coverage.
- An authorised unavailable state keeps the accepted transcript readable. Revocation still denies the route, renders no transcript content, and clears browser-tab drafts on that denial.
- Evidence revision now locks the authoritative Interview Session and source Question before replay detection. Concurrent identical legacy retries settle as one appended `REVISION` Evidence item and one runtime-work row; stale browser revisions retain their existing conflict behavior.

## Automated evidence

- Focused keyboard acceptance: `FINDWORKS_E2E_PORT=8168 npx playwright test tests/e2e/interview-accessibility.spec.ts -g 'keyboard-only'` — 2 tests passed.
- Full suite: `FINDWORKS_E2E_PORT=8168 bash scripts/test.sh` — 97 JVM tests and 38 Playwright browser tests passed.
- [`tests/e2e/interview-accessibility.spec.ts`](../../tests/e2e/interview-accessibility.spec.ts) reads Chromium's complete exposed accessibility tree with CDP `Accessibility.getFullAXTree`; it does not infer semantics only from DOM attributes.
- Initial Question, working, next Question, paused, runtime-failed, confirmation, completed, and revision surfaces each expose exactly one `data-focus-target` and exactly one focused accessibility-tree heading with the expected accessible name. The deliberately held submitting state instead exposes exactly one focused `Sending…` button with `disabled=true`, the visible `Waiting for FindWorks to confirm…` status, and exactly one polite/assertive live-region source.
- Every stable surface has at most one polite/assertive live-region source, and the focused heading is not duplicated as a live-region name. Every surface exposes exactly one `log` transcript and zero `complementary` landmarks, including fixtures containing 21 saved turns and an appended revision.
- The tree check rejects unnamed buttons, disclosure controls, links, radios, or textboxes. It verifies the initial required own-words textbox; enabled submit, uncertainty, clarification, pause, discomfort, early-end, resume, retry, completion, and revision controls; collapsed ownership disclosure; unchecked named structured-response radios; and the optional named explanation textbox.
- Revision mode exposes exactly one required `Add a correction` textbox, one revision form, no current-answer form, and no own-words or structured-answer editor. Paused, runtime-failed, confirmation, and completed states expose no textbox.
- Genuine keyboard-only browser traversal starts from the focused current surface, uses `Tab`, `Shift+Tab`, `Enter`, and typed keyboard input to reach a correction from a 21-turn active transcript, traverse its editor/cancel/append controls, submit the append, and traverse the available actions in paused, runtime-failed, confirmation, unavailable, and completed lifecycle states. These checks sit alongside, rather than being inferred from, the accessibility-tree assertions.
- Browser coverage additionally verifies 24-hour Session-scoped draft expiry, cross-Session isolation, acknowledged-submit clearing, completion/unavailable/revocation clearing, readable authorised unavailable transcripts, a realistic 21-answered-turn/22-question session with an appended revision, fresh mobile landing focus without manual scrolling, lifecycle offline protection, computed small-text contrast, narrow and wide overflow checks, correction history, and Clerk continuity.
- JVM coverage verifies server-rendered exclusive revision mode, original-plus-appended Evidence, stale-revision protection, readable authorised unavailable transcripts without post-revocation disclosure, progress language across unaddressed, exploring, and terminal Investigation Result states, and a deterministically blocked concurrent revision regression asserting exactly one `REVISION` Evidence item and one runtime-work row.

## Residual assistive-technology limitation

This headless Linux host cannot run the requested manual NVDA or VoiceOver journey. No NVDA speech transcript, VoiceOver transcript, or human screen-reader usability result was produced, and this record does not present Chromium's accessibility tree as a substitute for one. The automated evidence rigorously verifies the browser semantics delivered to assistive technology and guards against the specific duplicate-focus, duplicate-live-source, landmark, naming/state, and competing-editor failures; actual announcement timing, verbosity, and usability in NVDA on Windows or VoiceOver on macOS remain unverified residual risk.

## Visual evidence

The original confirmation round rebuilt the visual stack and captured both viewports from the same realistic 21-turn session. Those active-conversation visuals were unchanged by this correction batch and were not recaptured:

- Mobile, 390 × 844 viewport: [`.impeccable/review/mobile.png`](../../.impeccable/review/mobile.png)
- Widescreen, 1440 × 1000 viewport: [`.impeccable/review/desktop.png`](../../.impeccable/review/desktop.png)

The full-page captures show readable chronological scanning, a stable 700px widescreen measure, no narrow-screen horizontal overflow, a visible appended correction with `Correction · saved` metadata, authoritative `Exploring 1 key area` progress, and one composer at the end of history. The confirmation assertions also checked one visible textarea, the actual revision marker, and no horizontal overflow at both viewports. The unavailable-state transcript correction is covered by rendered JVM and browser assertions rather than a new visual claim.

## Mechanical detector

The Impeccable detector ran once during the original UI completion. It was deliberately not rerun for this bounded finish-review fix batch, as requested. Its original result reported 140 findings: 4 warnings and 136 design-system advisories.

The warnings were reviewed as non-blocking:

- one pre-existing side-border treatment in the Investigator findings review, outside this Interviewee surface;
- the existing global Inter declaration;
- two flat-type-hierarchy false positives from scanning Thymeleaf templates/fragments without their resolved stylesheet. The batched rendered inspection shows the actual serif Question hierarchy.

Most advisories identify literal colors/type steps already present across the shared stylesheet. `DESIGN.md` now records the Interviewee warm-paper palette, durable transcript layout, editorial Question type, Evidence cards, composer, progress, and lifecycle rules. The raw original detector result was retained at `/tmp/issue68-impeccable-detector.json` for that run. This final acceptance batch adds the Chromium full-accessibility-tree checks recorded above. It does not claim a new NVDA or VoiceOver transcript.

## Integration

`integration/adaptive-interview-evaluation` was merged after completion; Git reported the ticket branch was already up to date.
