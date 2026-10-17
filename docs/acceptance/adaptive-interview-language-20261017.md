# Adaptive interview language acceptance — 17 October 2026

## Scope

This acceptance run checks the Interview Session runtime at its semantic turn seam. It does not alter or replay a live Mission.

The scenario deliberately supplies systems-oriented Mission wording, then gives three simulated interviewee answers using workplace terms. The production runtime model must:

- stay on the same Investigation Item while its thread remains open;
- ask one information need at a time;
- adopt terminology from the latest interviewee answer;
- avoid exposing Mission planning or architecture language;
- avoid inventing alternatives inside an open question;
- default to an own-words response;
- avoid repeating an earlier Question.

## Runtime

- Model: `openai/gpt-5.4-mini`
- Thinking level: `low`
- Runtime image: `findworks-runtime:adaptive-conversation-20261017`
- Image ID: `sha256:91f470f1347b0391c13732fd659ce15568e6520a037fa8dc8e4621f34add0451`
- Harness: `runtime/adaptive-sequence.acceptance.mjs`

## Result

The model produced this sequence:

1. “When Maya is choosing which red job to deal with first, what makes one red job go ahead of another?”
2. “When the gap isn’t explained, what happens to that red job next?”
3. “When Jo has picked up one of those jobs, what tells you it’s finished?”

All sequence assertions passed. The Questions stayed within one thread, used `Maya`, `red job`, and `Jo` from the simulated interviewee's language, asked one open information need each, and did not expose the Mission's `architecture`, `decision matrix`, or `system state` wording.

## Repeat

With model credentials available to the Docker process:

```bash
FINDWORKS_RUNTIME_IMAGE=findworks-runtime:adaptive-conversation-20261017 \
FINDWORKS_MODEL=gpt-5.4-mini \
npm run test:adaptive-interview
```

When credentials are held by the deployed worker rather than the host shell, add:

```bash
FINDWORKS_DOCKER_HOST_CONTAINER=findworks-pilot-worker-1
```

This is model-backed acceptance evidence, not a deterministic unit test. Run it when the runtime skill, model, thinking level, or question contract changes.
