# 006 — Bridge the final findings-package decision

- **Status**: TODO
- **Commit**: 85484df
- **Severity**: MEDIUM
- **Category**: Missed opportunities — preventing a jarring change
- **Estimated scope**: 3 files, approximately 70 lines

## Problem

Accepting or rejecting a Findings Package is a rare, consequential action. Datastar currently replaces the whole `#findings-package` fragment in one frame. The decision controls disappear and `Decision · READY` becomes `Decision · ACCEPTED` or `Decision · REJECTED` with no visual bridge.

```html
<!-- src/main/resources/templates/findings-review.html:92-109 — current -->
<section class="package-decision" th:if="${package.state() == 'READY'}" aria-labelledby="package-decision-heading">
    <h2 id="package-decision-heading">Decide this package</h2>
    ...
    <form class="form decision-form" method="post" th:action="@{'/app/findings/' + ${findingsSession.id()} + '/accept'}" data-on:submit="@post(evt.currentTarget.action, {contentType: 'form'})">
        ...
        <button class="button" id="accept-findings-package" th:disabled="${pendingRequiredOutcomes > 0}">Accept exact package</button>
    </form>
```

```java
// src/main/java/com/findworks/web/FindingsController.java:299-306 — current
private String mutationResult(UUID sessionId, boolean datastarRequest, Authentication authentication,
                              Model model, HttpServletResponse response) {
    if (!datastarRequest) return "redirect:/app/findings/" + sessionId;
    findings(sessionId, authentication, model);
    response.setContentType(MediaType.TEXT_HTML_VALUE);
    response.setHeader("Datastar-Selector", "#findings-package");
    response.setHeader("Datastar-Mode", "outer");
    return "findings-review :: findingsPackage";
}
```

The transition must run only for final package Accept/Reject, never for the many item-level review mutations that use the same helper.

## Target

Use Datastar v1.0.3's `Datastar-Use-View-Transition: true` response header only on package Accept/Reject responses. Give the summary and decision block stable transition names:

```css
/* target */
.package-summary { view-transition-name: findings-package-summary; }
.package-decision { view-transition-name: findings-package-decision; }

::view-transition-old(findings-package-summary),
::view-transition-old(findings-package-decision) {
  animation: 120ms var(--ease-out) both findings-package-out;
}

::view-transition-new(findings-package-summary) {
  animation: 180ms var(--ease-out) both findings-package-in;
}

@keyframes findings-package-out {
  to {
    opacity: 0;
    transform: translateY(-2px);
  }
}

@keyframes findings-package-in {
  from {
    opacity: 0;
    transform: translateY(2px);
  }
}
```

Use the existing exact token:

```css
--ease-out: cubic-bezier(0.23, 1, 0.32, 1);
```

Reduced motion uses opacity only:

```css
@keyframes findings-package-fade-out { to { opacity: 0; } }
@keyframes findings-package-fade-in { from { opacity: 0; } }

@media (prefers-reduced-motion: reduce) {
  ::view-transition-old(findings-package-summary),
  ::view-transition-old(findings-package-decision) {
    animation: 120ms var(--ease-out) both findings-package-fade-out;
  }

  ::view-transition-new(findings-package-summary) {
    animation: 120ms var(--ease-out) both findings-package-fade-in;
  }
}
```

Unsupported browsers retain the current immediate, correct Datastar patch.

## Repo conventions to follow

- Datastar v1.0.3 is loaded by `src/main/resources/templates/findings-review.html:8`.
- The current HTML-response contract sets `Datastar-Selector` and `Datastar-Mode` in `FindingsController.mutationResult`. Datastar v1.0.3 also reads the exact response header `Datastar-Use-View-Transition` into its `useViewTransition` patch option.
- `src/main/resources/static/assets/app.css:27` already defines `--ease-out: cubic-bezier(0.23, 1, 0.32, 1)`.
- One-shot View Transition pseudo-elements may use keyframes; frequently retargeted card feedback remains CSS transitions.
- Existing package semantics, readiness validation, notes, and publication must remain server-authoritative.

## Steps

1. In `src/main/java/com/findworks/web/FindingsController.java`, immediately before returning from successful package `accept` and `reject`, set `response.setHeader("Datastar-Use-View-Transition", "true")` only when `datastarRequest` is true. Do not set this header in `decide`, `correct`, or `acknowledge`.
2. If both methods call a shared helper, pass an explicit boolean named `useViewTransition`; never infer package scope from URL strings or model state.
3. In `src/main/resources/static/assets/app.css`, add `view-transition-name: findings-package-summary` to the existing `.package-summary` rule and `view-transition-name: findings-package-decision` to `.package-decision`.
4. Add the four exact keyframe/view-transition rules from Target beside the package-decision styles. Animate only `opacity` and `transform`.
5. Add the opacity-only reduced-motion keyframes and media-query override from Target after the existing global reduced-motion block.
6. In `src/test/java/com/findworks/PilotShellIntegrationTest.java`, issue an authenticated Datastar package-accept request and assert status 200, `Content-Type: text/html`, `Datastar-Selector: #findings-package`, `Datastar-Mode: outer`, and `Datastar-Use-View-Transition: true`. Assert an item-level decision response does not include the view-transition header.
7. Extend `tests/e2e/interview.spec.ts` around `Accept exact package`. Confirm the package reaches `Decision · ACCEPTED`, notes remain visible, the URL and document instance do not change, and the package-decision controls are removed.

## Boundaries

- Do NOT enable View Transitions for individual Accept, Reject, Correct, or Acknowledge actions.
- Do NOT transition the entire document, sidebar, breadcrumbs, Evidence cards, or danger zone.
- Do NOT delay database mutation, publication, accessible text, or button availability until animation completion.
- Do NOT add bounce, scale, blur, confetti, or a motion dependency.
- Do NOT animate layout properties.
- Do NOT change the non-Datastar redirect fallback.
- If the installed Datastar bundle is no longer v1.0.3 or does not consume `Datastar-Use-View-Transition`, STOP and verify the current official API before implementing.

## Verification

- **Mechanical**:
  - Run `source scripts/use-java-25.sh && mvn test`; expect all Java tests to pass.
  - Run `FINDWORKS_E2E_PORT=18081 npm test`; expect all Playwright tests to pass.
  - Inspect the package decision response and confirm `Datastar-Use-View-Transition: true`; inspect an item decision and confirm it is absent.
- **Feel check**:
  - Accept one complete package and reject a separate package. The decision controls should leave in `120ms`; the updated package summary should settle over `180ms`.
  - In DevTools at 10% playback, confirm only the summary snapshot and decision block snapshot move by two pixels. Findings cards and the app shell must remain stationary.
  - Confirm clicking Accept once cannot visually replay the old decision controls after the server has committed.
  - Toggle `prefers-reduced-motion`; confirm all translation disappears and only a `120ms` opacity bridge remains.
  - Test Safari/Firefox fallback behavior if View Transitions are unavailable: the semantic patch must still complete instantly.
- **Done when**: Final package decisions have one restrained, scoped bridge; item-level review remains fast and still; unsupported and reduced-motion environments preserve correct state without displacement.
