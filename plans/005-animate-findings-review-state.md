# 005 — Animate the finding that just changed state

- **Status**: TODO
- **Commit**: 85484df
- **Severity**: MEDIUM
- **Category**: Missed opportunities — state indication
- **Estimated scope**: 4 files, approximately 55 lines

## Problem

Findings review now posts through Datastar and replaces `#findings-package` without a document navigation. The semantic state and color coding update correctly, but the specific card that changed has no brief visual bridge. On a long package, the investigator must visually reacquire the card after Accept, Reject, Correct, or Acknowledge.

```html
<!-- src/main/resources/templates/findings-review.html:61-65 — current -->
<form class="finding-actions" method="post" th:action="@{'/app/findings/' + ${findingsSession.id()} + '/knowledge/' + ${item.id()} + '/decision'}" data-on:submit="@post(evt.currentTarget.action, {contentType: 'form'})">
    <button class="button compact" name="decision" value="ACCEPTED">Accept</button>
    <button class="button secondary compact" name="decision" value="REJECTED">Reject</button>
</form>
<form class="form correction-form" method="post" th:action="@{'/app/findings/' + ${findingsSession.id()} + '/knowledge/' + ${item.id()} + '/correct'}" data-on:submit="@post(evt.currentTarget.action, {contentType: 'form'})">
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

Animating every card would be noisy. Only the card changed by the current request may receive motion.

## Target

Mark exactly one returned card with `.finding-state-updated`. It settles by two pixels while fading from `0.72` to full opacity. Existing semantic border/background transitions remain intact.

```css
/* target */
.finding-state-updated {
  opacity: 1;
  transform: translateY(0);
  transition:
    border-color 160ms var(--ease-out),
    background-color 160ms var(--ease-out),
    opacity 140ms var(--ease-out),
    transform 140ms var(--ease-out);
}

@starting-style {
  .finding-state-updated {
    opacity: 0.72;
    transform: translateY(2px);
  }
}
```

The existing token must remain exact:

```css
--ease-out: cubic-bezier(0.23, 1, 0.32, 1);
```

Reduced motion keeps semantic color and opacity feedback but removes displacement:

```css
@media (prefers-reduced-motion: reduce) {
  .finding-state-updated {
    transform: none;
    transition:
      border-color 160ms var(--ease-out),
      background-color 160ms var(--ease-out),
      opacity 100ms var(--ease-out) !important;
  }

  @starting-style {
    .finding-state-updated {
      opacity: 0.72;
      transform: none;
    }
  }
}
```

## Repo conventions to follow

- Global motion styles and `--ease-out` live in `src/main/resources/static/assets/app.css`; do not create another easing token.
- `src/main/resources/static/assets/app.css:517` already transitions finding border and background over `160ms var(--ease-out)`; preserve those properties and values.
- `src/main/resources/templates/findings-review.html` already encodes state with text and color. Motion supplements those cues and must never replace them.
- Datastar mutations render the `findingsPackage` Thymeleaf fragment through `FindingsController.mutationResult`; keep server rendering authoritative.
- Existing Playwright coverage in `tests/e2e/interview.spec.ts:177-203` and `:284-302` verifies that the document and scroll position survive review actions.

## Steps

1. In `src/main/java/com/findworks/web/FindingsController.java`, extend `mutationResult` with nullable `UUID updatedKnowledgeId` and `UUID updatedUnresolvedId` parameters. After `findings(sessionId, authentication, model)`, add both values to the model as `updatedKnowledgeId` and `updatedUnresolvedId`.
2. Pass the current `knowledgeId` and `null` from `decide` and `correct`. Pass `null` and the current `unresolvedId` from `acknowledge`. Pass `null, null` from package-level `accept` and `reject`; package decisions must not animate an arbitrary card.
3. In `src/main/resources/templates/findings-review.html`, append `finding-state-updated` to a Knowledge Item article only when `updatedKnowledgeId != null and updatedKnowledgeId == item.id()`. Append it to an unresolved article only when `updatedUnresolvedId != null and updatedUnresolvedId == item.id()`. Preserve every existing state class expression.
4. Add the settled CSS and `@starting-style` block from Target beside the existing `.finding` state styles in `src/main/resources/static/assets/app.css`.
5. Add the component-specific reduced-motion block after the global reduced-motion declaration so the global `0.01ms !important` rule does not erase the useful opacity/color feedback.
6. Extend `tests/e2e/interview.spec.ts`. After Accept, Reject, Correct, and Acknowledge, assert that exactly one card has `.finding-state-updated`; assert the untouched cards do not. Check computed transition properties on that card are exactly `border-color`, `background-color`, `opacity`, and `transform`, with `opacity` and `transform` at `0.14s` and the existing colors at `0.16s`.
7. Preserve the current assertions that `window.__findingsReviewDocument` remains unchanged and `scrollY` differs by no more than one CSS pixel.

## Boundaries

- Do NOT animate all findings, the whole Investigation Item, or the complete findings package for an item-level decision.
- Do NOT scroll, focus, pulse, bounce, flash, or scale the changed card.
- Do NOT animate height, margin, padding, border width, or another layout property.
- Do NOT delay the Datastar request or its accessible text update.
- Do NOT change findings-review semantics, state colors, copy, persistence, or package-readiness rules.
- Do NOT add JavaScript or a dependency.
- If Datastar no longer patches `#findings-package` in `outer` mode, STOP and report drift instead of choosing a new seam.

## Verification

- **Mechanical**:
  - Run `source scripts/use-java-25.sh && mvn test`; expect all Java tests to pass.
  - Run `FINDWORKS_E2E_PORT=18081 npm test`; expect all Playwright tests to pass.
  - Confirm one and only one returned card has `.finding-state-updated` after each item-level mutation.
- **Feel check**:
  - Review a long package at desktop and 390px mobile widths. Accept one claim midway down the page and confirm the same card receives a restrained two-pixel settling cue without losing scroll position.
  - Repeat rapidly with Accept then Reject. CSS transitions must retarget cleanly; no card may restart from a large offset or move surrounding content.
  - In DevTools, set playback to 10% and confirm only the changed card moves, by exactly two pixels, while its semantic colors settle.
  - Toggle `prefers-reduced-motion`; confirm translation disappears while the changed card still receives brief opacity and color feedback.
- **Done when**: Every item-level review mutation highlights exactly the changed card for `140ms`, all other cards remain still, document and scroll position remain unchanged, and reduced-motion mode has no displacement.
