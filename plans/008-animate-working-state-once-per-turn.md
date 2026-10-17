# 008 — Animate the interview working state once per turn

- **Status**: TODO
- **Commit**: 85484df
- **Prerequisite**: Datastar SSE interview-state delivery (`V7__interview_session_notifications.sql` and `InterviewEventStream`)
- **Severity**: LOW
- **Category**: Missed opportunities — state indication
- **Estimated scope**: 2 files, approximately 35 lines

## Problem

After an answer is accepted, the Interview renders a working state while the runtime prepares the next semantic state:

```html
<section class="working-state" th:if="${working}" role="status" aria-live="polite">
    <div id="interview-stream" data-init="@get('/interview/events')"></div>
    <div id="interview-state-trigger"></div>
    <hr class="working-rule">
    <h1>Preparing your next question</h1>
    <p class="subtle">Your answer is safe. This usually takes a moment.</p>
</section>
```

The previous two-second meta refresh has been removed. A PostgreSQL notification now drives one SSE event, and Datastar replaces `#interview-shell` only when a question, completion proposal, or runtime failure is ready. Therefore the working state is created once per accepted answer and no longer needs JavaScript or storage to prevent animation replay.

## Target

Give `.working-state` one restrained entrance:

```css
.working-state {
  opacity: 1;
  transform: translateY(0);
  transition: opacity 160ms var(--ease-out), transform 160ms var(--ease-out);
}

@starting-style {
  .working-state {
    opacity: 0;
    transform: translateY(2px);
  }
}
```

Use the existing exact token:

```css
--ease-out: cubic-bezier(0.23, 1, 0.32, 1);
```

Reduced motion keeps a short opacity cue and removes translation:

```css
@media (prefers-reduced-motion: reduce) {
  .working-state {
    transform: none;
    transition: opacity 120ms var(--ease-out) !important;
  }

  @starting-style {
    .working-state {
      opacity: 0;
      transform: none;
    }
  }
}
```

## Repo conventions to follow

- Global motion styles and `--ease-out` live in `src/main/resources/static/assets/app.css`; do not create another easing token.
- Completed plan 002 owns the arriving `.interview-stage` transition. This plan owns only the preceding working state.
- Datastar and `InterviewEventStream` own state delivery. Motion must remain CSS-only and must not participate in transport or state decisions.
- Text and `role="status"` provide the semantic cue; motion only bridges its arrival.

## Steps

1. Confirm `src/main/resources/templates/interview-question.html` contains no `http-equiv="refresh"` and that the working state opens `/interview/events` through `data-init`.
2. In `src/main/resources/static/assets/app.css`, add the settled `.working-state` opacity, transform, and exact `160ms var(--ease-out)` transitions beside `.working-rule`.
3. Add the top-level `@starting-style` block from Target immediately after that rule.
4. Add the component-specific reduced-motion override after the global reduced-motion declaration so opacity remains perceptible while displacement is removed.
5. Extend `tests/e2e/interview.spec.ts`. After answer submission, assert the working state is visible and has only `opacity, transform` transitions at `0.16s`. Trigger the runtime and assert the next state arrives without replacing the document.
6. Observe requests during the test: exactly one `/interview/events` stream and one event-triggered `/interview/state` request may occur per working state; there must be no interval requests or document refreshes.

## Boundaries

- Do NOT reintroduce meta refresh, interval polling, periodic fetches, session storage, local storage, timers, or custom JavaScript.
- Do NOT animate the SSE trigger elements, accepted answer, masthead, answer history, or whole document.
- Do NOT change or duplicate the completed `.interview-stage` transition from plan 002.
- Do NOT loop, pulse, bounce, scale, blur, or animate a progress indicator.
- Do NOT delay rendering, SSE connection establishment, runtime work, accessible status text, or the Datastar patch.
- If the page no longer uses event-driven SSE delivery, STOP and restore that architectural prerequisite rather than adding replay guards around polling.

## Verification

- **Mechanical**:
  - Run `source scripts/use-java-25.sh && mvn test`; expect all Java tests to pass.
  - Run `FINDWORKS_E2E_PORT=18081 npm test`; expect all Playwright tests to pass.
  - Confirm there is no refresh meta tag, interval attribute, polling timer, or working-state JavaScript.
- **Feel check**:
  - Submit an answer and confirm the working message settles upward by only two pixels over `160ms`.
  - Let runtime work remain pending. The working state must remain completely still for the lifetime of the SSE connection.
  - When the SSE event arrives, confirm plan 002's question entrance runs once and the document remains unchanged.
  - Toggle `prefers-reduced-motion`; confirm the working state uses opacity for `120ms` with no translation.
- **Done when**: The working state enters once per accepted answer, remains still while waiting on SSE, never polls or refreshes the document, and uses no positional movement under reduced motion.
