# 002 — Bridge the arrival of each adaptive question

- **Status**: DONE
- **Commit**: 85484df
- **Severity**: MEDIUM
- **Category**: Missed opportunities — preventing a jarring change
- **Estimated scope**: 2 files, approximately 35 lines

## Problem

After an answer is accepted, the Interview page renders a working state and refreshes every two seconds. When runtime work completes, the next response replaces the working state with the question stage in a new document. The question currently appears at full opacity and position with no bridge.

```html
<!-- src/main/resources/templates/interview-question.html:5-6 — current -->
<meta th:if="${working}" http-equiv="refresh" content="2">
<title>Interview · FindWorks</title>
```

```html
<!-- src/main/resources/templates/interview-question.html:22-29 — current -->
<section class="working-state" th:if="${working}" role="status" aria-live="polite">
    <hr class="working-rule">
    <h1>Preparing your next question</h1>
    <p class="subtle">Your answer is safe. This usually takes a moment.</p>
</section>

<section class="interview-stage" th:unless="${working}" aria-labelledby="active-question">
```

The working page may reload repeatedly, so animating `.working-state` would replay motion every two seconds. Only the newly available `.interview-stage` should enter.

## Target

Use a CSS transition with `@starting-style` on `.interview-stage` only:

```css
/* target */
.interview-stage {
  opacity: 1;
  transform: translateY(0);
  transition: opacity 200ms var(--ease-out), transform 200ms var(--ease-out);
}

@starting-style {
  .interview-stage {
    opacity: 0;
    transform: translateY(4px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .interview-stage {
    transform: none;
    transition: opacity 200ms var(--ease-out) !important;
  }

  @starting-style {
    .interview-stage {
      opacity: 0;
      transform: none;
    }
  }
}
```

Use the audit-standard curve exactly:

```css
--ease-out: cubic-bezier(0.23, 1, 0.32, 1);
```

The transition must begin immediately, finish in `200ms`, and never delay focus or interaction. The textarea's existing server-rendered autofocus behavior must remain unchanged.

## Repo conventions to follow

- Interview layout and all global motion rules live in `src/main/resources/static/assets/app.css`.
- `.interview-stage` already owns spacing at `src/main/resources/static/assets/app.css:507` and is the narrowest safe selector for this transition.
- The global reduced-motion rule at `src/main/resources/static/assets/app.css:646-647` forces transition duration to `0.01ms !important`; the component-specific reduced-motion declaration must appear later or have sufficient specificity and `!important` so the helpful opacity transition remains `200ms` while movement is removed.
- Entry motion uses `cubic-bezier(0.23, 1, 0.32, 1)` from the audit playbook, not bare `ease` or `ease-in`.
- Animate only `transform` and `opacity`.

## Steps

1. In `src/main/resources/static/assets/app.css`, add `--ease-out: cubic-bezier(0.23, 1, 0.32, 1);` to `:root` if it is absent. If plan 001 already added it, reuse the existing token unchanged.
2. Expand the existing `.interview-stage` declaration with the settled opacity, transform, and exact `200ms var(--ease-out)` transitions shown in Target.
3. Add the top-level `@starting-style` block immediately after the base Interview stage rules. Do not target `.working-state`, `.interview-shell`, or the accepted-answer panel.
4. Add the component-specific reduced-motion block after the current global reduced-motion rule, or refactor ordering so `.interview-stage` retains an opacity-only `200ms` transition under reduced motion. Do not weaken reduced-motion behavior for any other component.
5. Add a focused assertion to `tests/e2e/interview.spec.ts` around the flow at lines 108-111. After `/test/runtime/run-once` produces the next question, assert the question is usable immediately. Use `page.evaluate` to confirm computed transition properties include only `opacity` and `transform`; do not add a sleep to production behavior.

## Boundaries

- Do NOT animate `.working-state`; its two-second meta refresh would replay the entrance.
- Do NOT crossfade entire documents, the accepted answer, or previous-answer history.
- Do NOT delay autofocus, pointer input, keyboard input, or form submission until the transition finishes.
- Do NOT add JavaScript or a motion dependency.
- Do NOT animate height, margin, padding, top, or another layout property.
- If `.interview-stage` no longer uniquely identifies the ready-question state from commit `85484df`, STOP and report drift.

## Verification

- **Mechanical**:
  - Run `source scripts/use-java-25.sh && mvn test`; expect all Java tests to pass.
  - Run `FINDWORKS_E2E_PORT=18081 npm test`; expect all Playwright tests to pass.
  - Confirm the computed transition property for `.interview-stage` is `opacity, transform`, duration is `0.2s`, and timing function resolves from `--ease-out`.
- **Feel check**:
  - Complete one answer, leave the working screen visible, run the test runtime worker, and watch the next question arrive.
  - Confirm the working page remains completely still through repeated two-second refreshes.
  - In DevTools, set animation playback to 10% and confirm the question moves only four pixels upward while fading in; there must be no scale, blur, or content reflow.
  - Confirm the answer textarea can receive input before the transition ends.
  - Toggle `prefers-reduced-motion`; confirm position movement disappears while a brief opacity bridge remains.
- **Done when**: Each ready question enters once in `200ms`, repeated working refreshes remain still, input is never delayed, and reduced-motion mode preserves only the opacity cue.
