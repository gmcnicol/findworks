# 001 — Require a deliberate hold for destructive deletion

- **Status**: DONE
- **Commit**: 85484df
- **Severity**: HIGH
- **Category**: Missed opportunities — feedback
- **Estimated scope**: 5 files, approximately 170 lines

## Problem

Deleting a Discovery or Interview Session is irreversible from the user's point of view, but both actions currently submit on an ordinary click. The interface gives no progressive feedback that a destructive commitment is underway and does not distinguish an intentional hold from a slip.

```html
<!-- src/main/resources/templates/discovery-detail.html:25 — current -->
<form method="post" th:action="@{/app/discoveries/{id}/delete(id=${discovery.id()})}"><button class="button danger" type="submit">Delete Discovery</button></form>
```

```html
<!-- src/main/resources/templates/findings-review.html:97 — current -->
<form method="post" th:action="@{'/app/interviews/' + ${findingsSession.id()} + '/delete'}"><button class="button secondary">Delete Interview Session</button></form>
```

The current shared button feedback is only a one-pixel downward shift:

```css
/* src/main/resources/static/assets/app.css:146-150 — current */
transition: background-color 120ms ease, border-color 120ms ease, transform 80ms ease;
}

.button:hover { background: var(--action-hover); color: #fff; }
.button:active { transform: translateY(1px); }
```

## Target

Progressively enhance only the two destructive forms into accessible press-and-hold controls:

- Pointer or keyboard hold duration: exactly `2000ms`.
- Press feedback: `transform: scale(0.97)` over `160ms var(--ease-out)`.
- Hold progress: a button overlay whose `transform` moves from `scaleX(0)` to `scaleX(1)` over `2000ms linear`, with `transform-origin: left center`.
- Cancellation: release, pointer cancellation, pointer leaving the button, lost focus, or hidden document returns the overlay to `scaleX(0)` over `160ms var(--ease-out)`.
- Completion: submit exactly once through `form.requestSubmit(button)` and change the accessible status to `Deleting…` before navigation.
- Reduced motion: preserve the two-second safety requirement but remove positional and scale movement. Show hold state by transitioning overlay `opacity` from `0` to `0.18` over `200ms var(--ease-out)` and keep visible text plus an `aria-live="polite"` message.
- JavaScript failure fallback: retain the current native form submission. Progressive enhancement must only suppress ordinary clicks after the module has initialized the control.

Add this shared token once in `:root`:

```css
--ease-out: cubic-bezier(0.23, 1, 0.32, 1);
```

Target CSS shape:

```css
.hold-confirm {
  position: relative;
  overflow: hidden;
  isolation: isolate;
  transition: background-color 120ms ease, border-color 120ms ease,
              transform 160ms var(--ease-out);
}

.hold-confirm::before {
  position: absolute;
  inset: 0;
  z-index: -1;
  background: rgba(255, 255, 255, 0.2);
  content: "";
  transform: scaleX(0);
  transform-origin: left center;
  transition: transform 160ms var(--ease-out);
}

.hold-confirm.is-holding {
  transform: scale(0.97);
}

.hold-confirm.is-holding::before {
  transform: scaleX(1);
  transition-duration: 2000ms;
  transition-timing-function: linear;
}

@media (prefers-reduced-motion: reduce) {
  .hold-confirm,
  .hold-confirm.is-holding {
    transform: none;
    transition: background-color 120ms ease, border-color 120ms ease !important;
  }

  .hold-confirm::before {
    transform: none;
    opacity: 0;
    transition: opacity 200ms var(--ease-out) !important;
  }

  .hold-confirm.is-holding::before {
    transform: none;
    opacity: 0.18;
  }
}
```

The exact overlay color may differ between the red Discovery control and the Interview Session control, but its animated properties and timings must not differ.

## Repo conventions to follow

- Global visual tokens and component CSS live in `src/main/resources/static/assets/app.css`.
- Static behavior is implemented as small dependency-free ES modules, as in `src/main/resources/static/assets/manual-invitation.js`.
- Templates remain server-rendered Thymeleaf forms with native POST actions and CSRF handling.
- The application already uses `aria-live="polite"` for copy feedback in `src/main/resources/templates/manual-invitation-link.html:23`.
- Use the audit-standard strong exit curve exactly: `cubic-bezier(0.23, 1, 0.32, 1)`.
- Animate only `transform` and `opacity`; do not animate width, left, padding, or another layout property.

## Steps

1. In `src/main/resources/static/assets/app.css`, add `--ease-out: cubic-bezier(0.23, 1, 0.32, 1);` to `:root`. If the token already exists with that exact value because another animation plan ran first, reuse it and do not duplicate it.
2. Add the `.hold-confirm`, `.hold-confirm::before`, `.hold-confirm.is-holding`, and reduced-motion rules shown in the Target. Ensure button text remains above the fill using an inner span with `position: relative; z-index: 1` if required by the final stacking context.
3. Create `src/main/resources/static/assets/hold-to-confirm.js`. Select every `[data-hold-confirm]` button and initialize it once. After initialization, add a marker such as `data-hold-ready="true"`; suppress normal click submission only while that marker is present.
4. In the module, start one `2000ms` timer on `pointerdown`, or on non-repeating `keydown` for `Space` or `Enter`. Add `.is-holding`, update visible button text to `Keep holding…`, and announce `Keep holding to confirm deletion.` Do not start multiple timers.
5. Cancel the timer and restore the original label on `pointerup` before the deadline, `pointercancel`, `pointerleave`, `keyup` before the deadline, `blur`, or `document.visibilitychange` when hidden. Announce `Deletion cancelled.` Avoid announcing cancellation if no hold was active.
6. At the deadline, clear the active state, set the label and live status to `Deleting…`, disable the button, and invoke `button.form.requestSubmit(button)` exactly once. Guard against the synthetic click produced by keyboard activation.
7. In `src/main/resources/templates/discovery-detail.html`, load `/assets/hold-to-confirm.js` as a module. Add `data-hold-confirm`, an original-label data attribute, and an adjacent `role="status" aria-live="polite"` status element to the Discovery deletion form. Wrap the visible button label if the stacking implementation requires it.
8. Apply the same markup contract in `src/main/resources/templates/findings-review.html`. Change the Interview Session deletion button to use the existing `.danger` treatment so both irreversible actions communicate the same severity.
9. Update `tests/e2e/interview.spec.ts:242`, `:347`, and `:371`. First prove an ordinary short click or sub-two-second hold does not delete. Then focus the button, hold `Space` for at least `2100ms`, release it, and assert the existing post-deletion destination or `404` behavior. Keep the tests deterministic; do not reduce the production duration through a test-only branch.

## Boundaries

- Do NOT change deletion endpoints, retention semantics, authorization, CSRF behavior, or database operations.
- Do NOT apply hold-to-confirm to rejection, revocation, sign-out, or ordinary workflow actions.
- Do NOT add a modal, confirmation checkbox, animation library, or third-party dependency.
- Do NOT remove native form behavior before the JavaScript module has initialized successfully.
- Do NOT animate layout properties.
- If any cited form no longer performs the described deletion at commit `85484df`, STOP and report drift instead of improvising.

## Verification

- **Mechanical**:
  - Run `source scripts/use-java-25.sh && mvn test`; expect all Java tests to pass.
  - Run `FINDWORKS_E2E_PORT=18081 npm test`; expect all Playwright tests to pass.
  - In Playwright, verify a short click leaves the resource available and a completed two-second keyboard hold deletes it once.
- **Feel check**:
  - Test both controls with mouse, touch emulation, `Space`, and `Enter`.
  - Confirm release at roughly 25%, 75%, and 95% visibly snaps the fill back in `160ms`, while completion occurs only at 100%.
  - Confirm moving the pointer outside the button cancels the action.
  - In DevTools, set animation playback to 10% and confirm the fill grows from the left edge without changing button dimensions or moving its label.
  - Toggle `prefers-reduced-motion`; confirm the two-second hold still applies, positional movement disappears, and opacity plus text still communicate progress.
- **Done when**: Neither resource can be deleted by a plain enhanced click; pointer and keyboard holds submit exactly once after two seconds; cancellation is immediate and legible; no layout shift occurs; and the no-JavaScript fallback remains the existing native POST.
