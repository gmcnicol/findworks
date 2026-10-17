# 004 — Give interview completion a restrained arrival

- **Status**: DONE
- **Commit**: 85484df
- **Severity**: LOW
- **Category**: Missed opportunities — rare delight
- **Estimated scope**: 3 files, approximately 35 lines

## Problem

Completing an Interview is a rare, meaningful endpoint, but its terminal screen is rendered with the same static panel treatment as pauses, runtime failures, expired invitations, and errors. There is no visual beat to confirm closure.

```html
<!-- src/main/resources/templates/interview-complete.html:3-10 — current -->
<head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Interview complete · FindWorks</title><link rel="stylesheet" th:href="@{/assets/app.css}"></head>
<body class="participant-body">
<main class="shell centered">
    <section class="panel state-panel">
        <p class="eyebrow">Interview complete</p>
        <h1>Thank you</h1>
        <p>Your interview is complete. Your accepted answers remain available to the Investigator.</p>
    </section>
</main>
```

The shared `.state-panel` is intentionally reused elsewhere, so animating that class globally would wrongly add delight to recovery and error states.

## Target

Scope the entrance to the completion document only by adding `interview-complete-body` to the body. Use the audit-standard strong entry curve and a near-full initial scale:

```css
--ease-out: cubic-bezier(0.23, 1, 0.32, 1);
```

```css
.interview-complete-body .state-panel {
  opacity: 1;
  transform: translateY(0) scale(1);
  transition: opacity 200ms var(--ease-out), transform 200ms var(--ease-out);
}

@starting-style {
  .interview-complete-body .state-panel {
    opacity: 0;
    transform: translateY(6px) scale(0.97);
  }
}

@media (prefers-reduced-motion: reduce) {
  .interview-complete-body .state-panel {
    transform: none;
    transition: opacity 200ms var(--ease-out) !important;
  }

  @starting-style {
    .interview-complete-body .state-panel {
      opacity: 0;
      transform: none;
    }
  }
}
```

The panel enters once in `200ms`. There is no bounce, confetti, icon drawing, text stagger, looping motion, or delayed content. The screen is fully interactive and readable from its first frame.

## Repo conventions to follow

- Participant-state visuals are defined in `src/main/resources/static/assets/app.css` beside `.state-panel` at lines 559-561.
- `src/main/resources/templates/interview-complete.html` already has the narrowest route-specific document; add a body class rather than branching server logic.
- Use `cubic-bezier(0.23, 1, 0.32, 1)` from the audit playbook for entry motion.
- Initial scale must be `0.97`, never `0`.
- Animate only `transform` and `opacity`.
- Keep the product's calm, editorial Interviewee character documented in `DESIGN.md`; delight here is closure, not celebration theatre.

## Steps

1. In `src/main/resources/static/assets/app.css`, add `--ease-out: cubic-bezier(0.23, 1, 0.32, 1);` to `:root` if absent. Reuse it unchanged if another plan already added it.
2. In `src/main/resources/templates/interview-complete.html`, change `<body class="participant-body">` to `<body class="participant-body interview-complete-body">`. Do not alter the panel's text, heading structure, or shared classes.
3. Add the completion-scoped settled styles and `@starting-style` block exactly as shown in Target. Keep the selector scoped to `.interview-complete-body .state-panel` so invitation, pause, failure, confirmation, 403, and 404 panels remain still.
4. Add the component-specific reduced-motion override after the current global reduced-motion rule so opacity remains `200ms` while translation and scale are removed.
5. Extend the completion flow in `tests/e2e/interview.spec.ts:118-119` to assert the body has `.interview-complete-body`, the panel remains visible and readable immediately, and computed transition properties contain only `opacity` and `transform`.

## Boundaries

- Do NOT animate any other `.state-panel` screen.
- Do NOT introduce bounce, springs, confetti, particles, sound, text stagger, or a completion delay.
- Do NOT change interview completion semantics, copy, redirects, or Evidence persistence.
- Do NOT add JavaScript or a dependency.
- Do NOT animate layout properties, shadows, filters, or colors.
- If the completion template no longer matches commit `85484df`, STOP and report drift instead of broadening the selector.

## Verification

- **Mechanical**:
  - Run `source scripts/use-java-25.sh && mvn test`; expect all Java tests to pass.
  - Run `FINDWORKS_E2E_PORT=18081 npm test`; expect all Playwright tests to pass.
  - Confirm the shared pause, runtime-failure, confirmation, invitation, 403, and 404 panels do not match the completion animation selector.
- **Feel check**:
  - Complete an Interview normally and watch the `Thank you` panel arrive once.
  - In DevTools, set animation playback to 10% and confirm the panel begins at `scale(0.97)` and six pixels lower—not from zero—and settles without overshoot.
  - Confirm text is never blurred, clipped, or delayed.
  - Test at 320px and desktop widths; the panel must not overflow or shift after settling.
  - Toggle `prefers-reduced-motion`; confirm scale and translation disappear while a brief opacity entrance remains.
- **Done when**: Only the successful completion screen receives one subtle `200ms` entrance, all recovery/error panels remain still, and reduced-motion mode preserves opacity without movement.
