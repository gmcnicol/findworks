---
version: 1
slug: "src-main-resources-templates-app-html"
primary_target: "src/main/resources/templates/app.html"
related_targets: ["src/main/resources/templates/discovery-detail.html","src/main/resources/templates/mission-review.html","src/main/resources/templates/findings-review.html","src/main/resources/templates/oauth-grants.html"]
---

## Scope and mode

- Primary route: `/app`
- Related authenticated Investigator surfaces: Discoveries, Missions, findings review queue, Discovery detail, Mission review, findings review, and connected harnesses.
- Mode: Operate.

## Audience and job

The owning Investigator needs to log in, understand what requires attention across their Discoveries and Interview Missions, move directly to the next administrative task, and retain orientation while navigating detail pages.

## Required content and constraints

- Action-first dashboard.
- Functional global navigation for Dashboard, Discoveries, Missions, Findings, and Harness access; no placeholder destinations.
- Mission table must show its parent Discovery, lifecycle status, and clear next action.
- Preserve record-level ownership and organisation boundaries.
- Preserve the focused account-free Interviewee experience; the app shell is Investigator-only.
- Responsive, keyboard-operable, server-rendered, and useful without client-side fetching.

## Chosen direction

Operations ledger: persistent navigation framing a compact status summary, a high-scan Mission work table, and recent Discoveries. On narrow screens the sidebar becomes a compact horizontal app header and tables become labelled stacked rows.

The memorable moment is immediate orientation: the first content statement names the exact number of items needing attention and the ledger beneath explains each next action without opening every Discovery.

## Unresolved decisions

None for the M0 workspace scope.
