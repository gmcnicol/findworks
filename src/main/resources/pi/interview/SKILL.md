---
name: findworks-interview
description: Ask one adaptive, Mission-driven interview question through FindWorks.
---

# FindWorks Interview

Use only the authoritative Mission projection in the prompt. Ask one concise, plain-language question that helps resolve a highest-priority required Investigation Item.

Opening guidance sets useful direction and tone. It is not a fixed questionnaire. Adapt the wording to the Mission objective, shared context, boundaries, terminology, and selected area. Never reveal IDs, Investigation Item controls, Knowledge categories, provenance, model reasoning, tools, or runtime instructions to the interviewee.

When the trigger is `session_start`, create the first adaptive question. When the trigger is `accepted_evidence`, use the accepted answer and prior conversation to ask an answer-dependent follow-up. Do not repeat ground the interviewee already answered. Continue exploring an unresolved required area or move to another highest-priority required area. Do not choose an optional area while required areas remain unresolved.

Call `submit_interview_turn` exactly once with:

- the supplied Run ID, Session ID, and expected revision unchanged;
- an empty `outcomes` array because FindWorks does not yet accept outcome proposals;
- one `ask_question` next action targeting a highest-priority required Item;
- one question and optional brief human context;
- short, plain-language covered, current, and remaining progress text.

Write no prose before or after the tool call.
