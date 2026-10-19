---
version: 1
slug: "sources-templates-interview-question-html-aa065ca6"
primary_target: "src/main/resources/templates/interview-question.html"
related_targets: ["src/main/resources/templates/interview-confirmation.html","src/main/resources/templates/interview-paused.html","src/main/resources/templates/interview-runtime-failed.html","src/main/resources/templates/interview-complete.html"]
---

# Interview conversation surface

Mode: Operate. Scope: the external Interviewee route family (`/interview`, its session-scoped form, and all paused/loading/completion/recovery states).

The Interviewee’s job is to answer one adaptive Question in their own words, understand what has been durably accepted, and safely leave or return without navigating an administrative workspace. The transcript, the single active composer, and calm lifecycle states are the content. Mission planning, Investigation Item identifiers, runtime internals, and private Investigator context remain absent.

## Direction contract

**THESIS:** A quiet, durable conversation ledger: the whole surface reads as one continuing human interview, not a questionnaire assembled from panels. It refuses repeated field labels, answer receipts detached from their Questions, and competing primary actions.

**OWN-WORLD:** Extend FindWorks’ warm interview paper, navy ink, cobalt action, restrained green acknowledgement, editorial Question serif, fine rules, and compact rounded controls. Interviewer turns remain open and text-led; Interviewee Evidence sits in a softly tinted inset with explicit Saved or Correction metadata. No chatbot avatars, bubbles, gradients, or ornamental chrome.

**STORY:** The Interviewee sees where they are, reads the durable transcript, answers exactly one current Question, and receives acknowledgement only after the server accepts Evidence. They can pause, request clarification, record an honest Unknown or Ownership Gap, append a correction, recover from runtime/network/revision failure, and return on another device with history intact.

**FIRST VIEWPORT:** A slim masthead and evidence-backed progress line sit above a narrow 700px reading column. On a new Session the current interviewer turn leads; after several turns the latest exchange anchors above a composer dock within normal document flow. The multiline own-words composer is the dominant control, with a compact cobalt send affordance at its lower right and secondary response routes below.

**FORM:** User-pinned issue #68 conversational transcript, the approved replacement for the previous form-first surface; code-led path. Seed key: `user-pinned-issue-68`. Signature interaction: a server-acknowledged answer settles into the transcript, then the real next Question arrives through the existing Datastar state patch with focus and a single polite announcement.

**FINISH:** unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance

Constraints: progressive HTML forms remain functional without JavaScript; server-owned state, Question semantics, response modes, optimistic revisions, create-once immutable Evidence, Clerk invitation/resumption, and existing Datastar event conventions remain authoritative. Do not add realtime transport, fake streaming, voice, or generated typing delays. Mobile-first from 320px; long answers and 20+ turns must remain readable. WCAG AA contrast, visible focus, reduced motion, keyboard submission, draft preservation, and non-disruptive live announcements are required.
