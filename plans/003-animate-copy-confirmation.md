# 003 — Animate invitation copy confirmation

- **Status**: DONE
- **Commit**: 85484df
- **Severity**: LOW
- **Category**: Missed opportunities — state indication
- **Estimated scope**: 4 files, approximately 65 lines

## Problem

Copying an invitation succeeds asynchronously, then replaces both the button and live-status text in the same frame. The state change is correct and accessible, but visually abrupt.

```html
<!-- src/main/resources/templates/manual-invitation-link.html:19-23 — current -->
<div class="copy-row">
    <input id="invitation-link" type="text" readonly th:value="${invitation.link()}" data-invitation-link>
    <button class="button" type="button" data-copy-invitation>Copy link</button>
</div>
<p class="subtle copy-status" role="status" aria-live="polite" data-copy-status>The link expires in seven days and works once.</p>
```

```js
// src/main/resources/static/assets/manual-invitation.js:5-13 — current
button?.addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(input.value);
    button.textContent = 'Copied';
    status.textContent = 'Copied. You can now paste the link into your message.';
  } catch {
    input.focus();
    input.select();
    status.textContent = 'Copy was blocked. The link is selected; copy it manually.';
  }
});
```

Repeatedly mutating `textContent` cannot transition by itself. The implementation needs a small inserted element for each new message, while preserving the button's accessible name and the existing live region.

## Target

Wrap initial text in stable hooks, then replace those child spans after the clipboard result:

```html
<!-- target shape -->
<button class="button copy-button" type="button" data-copy-invitation>
    <span data-copy-label>Copy link</span>
</button>
<p class="subtle copy-status" role="status" aria-live="polite" data-copy-status>
    <span data-copy-message>The link expires in seven days and works once.</span>
</p>
```

Every newly inserted success or failure span receives `.copy-feedback-text`:

```css
.copy-feedback-text {
  display: inline-block;
  opacity: 1;
  transform: translateY(0);
  transition: opacity 160ms var(--ease-out), transform 160ms var(--ease-out);
}

@starting-style {
  .copy-feedback-text {
    opacity: 0;
    transform: translateY(2px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .copy-feedback-text {
    transform: none;
    transition: opacity 200ms var(--ease-out) !important;
  }

  @starting-style {
    .copy-feedback-text {
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

The button must reserve enough width for both `Copy link` and `Copied` so the surrounding input never changes width. The animation begins only after clipboard success or failure; the initial page render must not animate these labels.

## Repo conventions to follow

- Copy behavior remains in the existing dependency-free module `src/main/resources/static/assets/manual-invitation.js`.
- The existing `role="status" aria-live="polite"` remains the announcement mechanism; do not create a second live region.
- Global component styling remains in `src/main/resources/static/assets/app.css` near `.copy-field`, `.copy-row`, and `.copy-status` at lines 566-569.
- Use `document.createElement` and `replaceChildren`, matching the defensive DOM style already used in `src/main/resources/static/assets/app.js`.
- Entry motion uses `cubic-bezier(0.23, 1, 0.32, 1)` and only `transform` plus `opacity`.

## Steps

1. In `src/main/resources/static/assets/app.css`, add `--ease-out: cubic-bezier(0.23, 1, 0.32, 1);` to `:root` if it is absent. Reuse the exact token if an earlier plan added it.
2. In `src/main/resources/templates/manual-invitation-link.html`, wrap the initial button label with `<span data-copy-label>` and the initial status message with `<span data-copy-message>`. Add `.copy-button` to the button. Preserve `data-copy-invitation`, `data-copy-status`, `role="status"`, and `aria-live="polite"`.
3. Add `.copy-button { min-width: 104px; }` on layouts where the button sizes to content. Preserve the existing full-width mobile behavior from the one-column `.copy-row`; verify that the minimum does not overflow at 320px.
4. Add `.copy-feedback-text`, its `@starting-style`, and its reduced-motion override exactly as specified in Target. Place the reduced-motion override after the current global rule so the opacity transition is not reduced to `0.01ms`.
5. In `src/main/resources/static/assets/manual-invitation.js`, keep references to the button and status containers. Add a helper that creates a `span`, gives it class `copy-feedback-text`, assigns a provided text with `textContent`, and returns it.
6. On success, call `button.replaceChildren(successLabelSpan)` with `Copied`, then `status.replaceChildren(successMessageSpan)` with `Copied. You can now paste the link into your message.` Do not animate or replace the input.
7. On failure, leave the button label as `Copy link`; replace only the status child with an animated span containing the existing failure message. Preserve `input.focus()` and `input.select()` before announcing failure.
8. Update `tests/e2e/interview.spec.ts:87-88` to preserve the current clipboard and accessible-status assertions. Add checks that the button remains accessible by the name `Copied`, the `.copy-row` width does not change by more than one CSS pixel, and the inserted success spans use only `opacity` and `transform` transitions.

## Boundaries

- Do NOT animate the invitation URL input, resize the copy row, or move focus after successful copying.
- Do NOT delay the clipboard operation or the `aria-live` update while waiting for animation.
- Do NOT use keyframes, timers, forced layout, Web Animations API, or a dependency.
- Do NOT animate color, width, height, margin, padding, blur, or other layout/paint-heavy properties.
- Do NOT add animation to ordinary form validation or every status message in the application.
- If the copy module no longer performs the mutations shown at commit `85484df`, STOP and report drift.

## Verification

- **Mechanical**:
  - Run `source scripts/use-java-25.sh && mvn test`; expect all Java tests to pass.
  - Run `FINDWORKS_E2E_PORT=18081 npm test`; expect all Playwright tests to pass, including clipboard readback.
  - Confirm the success button's accessible name is `Copied` and the existing live region contains the full success message immediately after the clipboard promise resolves.
- **Feel check**:
  - Copy once at desktop and 320px mobile widths. Confirm the input and button geometry remain fixed while the new text settles by two pixels.
  - In DevTools, set animation playback to 10% and verify only the newly inserted label and status span move; the button box and URL field must not move.
  - Trigger the blocked-clipboard path and confirm selection/focus occur immediately while only the failure message enters.
  - Toggle `prefers-reduced-motion`; confirm the two-pixel movement disappears and the new text still receives a `200ms` opacity transition.
- **Done when**: Success and failure feedback remain immediate and screen-reader-accessible, new text settles once without layout shift, and rapid repeat clicks cannot leave stale or overlapping messages.
