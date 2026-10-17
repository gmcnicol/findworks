# 007 — Give the invitation-link result a restrained arrival

- **Status**: TODO
- **Commit**: 85484df
- **Severity**: LOW
- **Category**: Missed opportunities — preventing a jarring change
- **Estimated scope**: 2 files, approximately 35 lines

## Problem

Generating a manual invitation performs a rare, consequential transition to a page containing a bearer link that is shown only once. The focused result panel currently appears at its settled position with no visual bridge.

```html
<!-- src/main/resources/templates/manual-invitation-link.html:13-14 — current -->
<main class="app-main centered-content">
    <section class="panel manual-invitation-panel" aria-labelledby="invitation-heading">
```

```css
/* src/main/resources/static/assets/app.css:637-639 — current */
.manual-invitation-panel { width: min(100%, 720px); }
.manual-invitation-panel h1 { margin-top: 0; }
.copy-field { display: grid; gap: 10px; margin: 28px 0 24px; }
```

The existing copy-confirmation animation occurs only after the Copy action and does not bridge arrival at the one-time result.

## Target

Apply one route-scoped entrance to `.manual-invitation-panel`:

```css
/* target */
.manual-invitation-panel {
  width: min(100%, 720px);
  opacity: 1;
  transform: translateY(0);
  transition: opacity 200ms var(--ease-out), transform 200ms var(--ease-out);
}

@starting-style {
  .manual-invitation-panel {
    opacity: 0;
    transform: translateY(6px);
  }
}
```

Use the existing exact token:

```css
--ease-out: cubic-bezier(0.23, 1, 0.32, 1);
```

Reduced motion retains a short opacity cue and removes movement:

```css
@media (prefers-reduced-motion: reduce) {
  .manual-invitation-panel {
    transform: none;
    transition: opacity 160ms var(--ease-out) !important;
  }

  @starting-style {
    .manual-invitation-panel {
      opacity: 0;
      transform: none;
    }
  }
}
```

The link and Copy control are readable and operable from the first frame; there is no delay or stagger.

## Repo conventions to follow

- `src/main/resources/static/assets/app.css:619-632` uses a route-specific `@starting-style` entrance for the Interview completion panel. Follow that structure but use translation only—no scale—for this administrative surface.
- `src/main/resources/static/assets/app.css:27` already owns `--ease-out: cubic-bezier(0.23, 1, 0.32, 1)`.
- Existing `.copy-feedback-text` motion at `app.css:640-651` remains independent and must not replay on initial panel arrival.
- The UI is a calm operations ledger. This is orientation, not celebration.

## Steps

1. In `src/main/resources/static/assets/app.css`, expand the existing `.manual-invitation-panel` rule with the exact settled opacity, transform, and `200ms var(--ease-out)` transitions from Target.
2. Add the top-level `@starting-style` block immediately after the manual invitation panel rules.
3. Add the component-specific reduced-motion override after the current global reduced-motion declaration so the helpful `160ms` opacity transition is not reduced to `0.01ms`.
4. Do not change `src/main/resources/templates/manual-invitation-link.html`; `.manual-invitation-panel` is already unique to this route and is the narrowest stable selector.
5. Extend `tests/e2e/interview.spec.ts` in `investigator generates and copies a manual invitation link`. On arrival, assert the heading and Copy button are enabled immediately. Read computed style and assert transition properties are exactly `opacity, transform`, both durations are `0.2s`, and both timing functions resolve to `cubic-bezier(0.23, 1, 0.32, 1)`.

## Boundaries

- Do NOT animate the app shell, input value, Copy button, warning, or individual text children on initial arrival.
- Do NOT add scale, bounce, blur, stagger, keyframes, timers, JavaScript, or a dependency.
- Do NOT delay rendering, focus, copying, or the one-time link disclosure.
- Do NOT alter invitation generation, token handling, security copy, or navigation.
- Do NOT change the existing Copy success/failure animation.
- If `.manual-invitation-panel` is reused by another route after commit `85484df`, STOP and add a route-specific body class rather than broadening the animation.

## Verification

- **Mechanical**:
  - Run `source scripts/use-java-25.sh && mvn test`; expect all Java tests to pass.
  - Run `FINDWORKS_E2E_PORT=18081 npm test`; expect all Playwright tests to pass.
  - Confirm computed transition properties are only `opacity` and `transform` and the normal duration is exactly `200ms`.
- **Feel check**:
  - Generate one manual invitation at desktop and 390px mobile widths. The panel should settle upward by six pixels over `200ms` while remaining readable and clickable immediately.
  - In DevTools at 10% playback, confirm the whole focused panel moves as one unit; none of its children should lag or stagger.
  - Copy the link immediately during the entrance and confirm clipboard behavior and copy feedback are unaffected.
  - Toggle `prefers-reduced-motion`; confirm translation disappears while a `160ms` opacity entrance remains.
- **Done when**: The one-time result panel enters once with a six-pixel, `200ms` bridge, never delays the sensitive task, and reduced-motion mode contains no positional movement.
