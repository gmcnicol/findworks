# Clare interview question/readability repair — 6 October 2026

Human reported an unintelligible, oversized Question after accepting the Clerk invitation. The saved Question dated 5 September asked about candidate capability names and an inventory without showing the relevant items. Approved opening guidance used similar jargon. The Mission referenced a 28-item inventory but did not include the list; the single existing answer did not contain it either.

The Investigator approved repair of the unanswered Question while preserving Evidence and the approved Mission. Typography was subsequently requested explicitly.

## Completed

- Active Question text uses 20px desktop / 18px mobile, 1.55 line height and a readable measure, replacing 56px / 36px display sizing. The stylesheet is live.
- Interview instructions require plain, self-contained business questions, forbid assuming unseen inventories, and distinguish missing Mission input from interviewee uncertainty.
- One real-runtime regeneration was attempted. It repeated the bad wording; this was rejected as a repair, not reported as success.
- An operator-authored reframe was accepted through the production scoped-turn commitment, with no outcome proposals:

  > Is there an important part of looking after clients or organising your accountancy practice’s work that your current tools don’t help with? A recent example would help.

- Runtime context now omits withdrawn, unanswered Questions and identifies retained Questions' active status. Historical records remain stored; accepted Evidence remains included. Updated worker deployed.
- Fingerprints confirmed original Evidence, exact approved Mission contents, Investigation Items and Clerk account assignment unchanged. One Evidence record remains. No further email sent.

## Verification and limits

The typography contract failed at 56px before the fix and passed at both desktop/mobile afterwards. Seven instruction-contract checks passed. A PostgreSQL-backed context regression failed with the withdrawn prompt included, then all 36 PilotShellIntegrationTest checks passed after the projection fix. Public health is healthy and the new worker has no Clerk credentials.

The repaired current Question is operator-authored, not proof of improved model quality. The failed model regeneration demonstrates why instruction-text checks alone are insufficient. Future human acceptance is still required. The missing inventory and the adequacy of historical coverage remain unresolved; this repair does not claim the 28 candidates were validated or revise the approved Mission silently.

Images: `findworks-web:interview-readability-20261006`, `findworks-worker:question-context-20261006`, `findworks-runtime:interview-grounding-20261006`. Previous overrides retained under `/home/gareth/deploy/findworks/` for rollback.
