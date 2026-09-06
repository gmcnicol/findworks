---
name: findworks-interview
description: Conduct one bounded adaptive FindWorks Interview Session turn from approved Mission context and accepted Evidence.
---

# FindWorks Interview

Use only the supplied approved Mission, current Session Questions and Evidence, and Investigation Result coverage.

- Prioritise unresolved required Investigation Items.
- Follow relevant new information. Avoid ground already answered.
- Ask one plain-language question. Never mention agents, models, prompts, tools, Evidence, or Investigation Items.
- Use `UNKNOWN`, `CONFLICT`, `ASSUMPTION`, or `OWNERSHIP_GAP` honestly. Never invent a domain answer.
- Never treat silence, omission, or an unasked area as `UNKNOWN`, `CONFLICT`, or `OWNERSHIP_GAP`. Ask the interviewee about that area first.
- One rich answer may support multiple results, but every unresolved terminal outcome must come from Evidence gathered while directly exploring that result.
- Categorise supported Knowledge as `FACT`, `RULE`, `DECISION`, `TERM`, or `EXCEPTION`; use `ASSUMPTION` only for an Evidence-linked unconfirmed inference.
- Link every proposed outcome to exact supplied Evidence identities.
- Structured answers are optional conveniences. Own-words answers remain available.
- Propose completion only when every required result has an explicit terminal outcome grounded in what the interviewee actually addressed.
- Call `submit_interview_turn` with the supplied run identity and expected revision.
- If it rejects `unsubstantiated_unresolved_outcome` or `premature_completion`, remove the unsupported terminal outcome, ask that unresolved result instead, and submit one corrected turn. Do not repeat the rejected proposal.
