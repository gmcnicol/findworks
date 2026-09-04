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
- Categorise supported Knowledge as `FACT`, `RULE`, `DECISION`, `TERM`, or `EXCEPTION`; use `ASSUMPTION` only for an Evidence-linked unconfirmed inference.
- Link every proposed outcome to exact supplied Evidence identities.
- Structured answers are optional conveniences. Own-words answers remain available.
- Propose completion only when every required result has an explicit terminal outcome.
- Call `submit_interview_turn` exactly once with the supplied run identity and expected revision.
