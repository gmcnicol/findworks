# Runtime Turn module deepening

Status: design confirmed on 6 October 2026. Implementation and deployment are not authorised by this confirmation.

## Goal and scope

Increase depth, locality and leverage by replacing the independent semantic-mutation implementation in `InvitationController.processRuntimeNext()` with deterministic proposals committed through the same module as production.

This is a strict behaviour-preserving refactor. Preserve the current production transport, semantic events, stable rejection codes, scoped credentials, expiry checks, revision checks, provenance rules, completion rules and replay behaviour.

## Module and seam

The shared Runtime Turn commitment module owns credential validation and the atomic transaction, including Evidence/outcome validation, outcome deduplication, next-action validation, Question creation or completion proposal, Session revision, run settlement, checkpoint commitment and credential consumption.

Its interface is the test surface: submit one scoped Runtime Turn and observe the existing stable event or rejection. HTTP details belong to the transport adapter. Callers must not receive a pre-authorised mutation shortcut or manage transaction ordering. A new language-level abstract interface is not required merely to extract the module.

Production and deterministic test adapters cross this same seam. Keep `/test/runtime/run-once` temporarily as a thin test adapter, retaining its development/test-only restriction. It may generate deterministic proposals and arrange scoped test credentials; it must not independently create semantic outcomes, Questions, completion proposals or committed revisions. Delete the duplicated semantic-mutation implementation rather than retaining both algorithms.

Process supervision, context projection, findings extraction and participant command orchestration remain unchanged. Existing fault simulations outside commitment are not proof of real process recovery and must remain labelled as simulations.

## Tests

- Preserve the production-transport replay and validation checks already in `PilotShellIntegrationTest`, including `turn_scoped_runtime_capability_commits_once_and_replays_only_the_stable_event`.
- Migrate successful journey fixtures to deterministic proposals through the shared commitment seam.
- Verify stable replay without duplicate Questions or outcomes; stale revision, wrong/expired credential and invalid Evidence scope reject without mutation.
- Verify required-coverage rejection and explicit interviewee completion confirmation remain unchanged.
- Verify submitted answers stay immutable and exactly one durable runtime-work record accompanies each accepted answer.
- Retain targeted transport/security checks. Replace duplicated mutation tests only once shared-seam checks cover their intended behaviour.
- Instruction-text checks and deterministic proposals do not prove real model adaptation or live process recovery.

## Preserved invariants

PostgreSQL remains authoritative; runtime execution remains at least once with atomic semantic commitment. Pi checkpoints remain secondary. Clerk authentication, research membership and Interview Access Grants remain independent. No model configuration, process isolation, Clerk credential handling or existing interview data changes belong in this refactor.

## Starting files

- `src/main/java/com/findworks/web/RuntimeTurnController.java`: existing production commitment implementation and transport adaptation.
- `src/main/java/com/findworks/web/InvitationController.java`: duplicate test runtime implementation.
- `src/main/java/com/findworks/web/RuntimeContainerWorker.java`: unchanged supervision and credential creation.
- `src/main/java/com/findworks/web/RuntimeTurnContext.java`: unchanged authoritative context projection.
- `src/test/java/com/findworks/PilotShellIntegrationTest.java` and `tests/e2e/`: existing observable journeys and fixtures.

The architecture report remains a temporary artefact at `/tmp/architecture-review-20261006T122009Z.html`. The domain definition of Runtime Turn is in `GLOSSARY.md`.
