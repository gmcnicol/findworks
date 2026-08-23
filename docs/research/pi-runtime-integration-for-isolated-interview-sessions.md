# Pi runtime integration for isolated Interview Sessions

Research date: 22 August 2026

## Conclusion

Pi supports two credible integration mechanisms for FindWorks: its TypeScript SDK and its stdin/stdout RPC mode. It does not document a hosted daemon, HTTP service, queue worker, or multi-tenant control plane. FindWorks would have to provide any network service and supervise whichever Pi runtime it uses.

For M0, the strongest candidate is one supervised `pi --mode rpc` child process per active Interview Session. This is a recommendation for the later architecture decision, not a settled product decision. Pi's own SDK guide says RPC is preferred when process isolation or a language-independent client matters, while the SDK is preferred for same-process type safety and direct programmatic customisation. A process boundary contains a Pi crash and gives each active Interview Session an unambiguous runtime, session file, skill set, tool set, and lifecycle. [Pi SDK: RPC alternative](https://pi.dev/docs/latest/sdk#rpc-mode-alternative)

That process boundary is not a security sandbox. Pi runs with the permissions of its operating-system user, and extensions run with the same permissions. Real security isolation requires an operating-system, container, VM, micro-VM, or policy-controlled sandbox boundary. For M0, the minimum safe configuration is therefore to disable all built-in filesystem and shell tools, disable ambient extension and skill discovery, load only the trusted FindWorks extension and approved interview skill, and give the extension narrowly scoped application credentials. Whether M0 additionally runs each Pi process in a container remains an architecture decision. [Pi security](https://pi.dev/docs/latest/security#no-built-in-sandbox), [Pi containerisation](https://pi.dev/docs/latest/containerization#choose-a-pattern), [Pi CLI resource and tool options](https://github.com/earendil-works/pi/blob/c49906ec77788625aacbdc53ebca6fbe65bd20f5/packages/coding-agent/README.md#cli-reference)

Pi should remain a conversational runtime and secondary runtime checkpoint. FindWorks must remain authoritative for the approved Interview Mission version, participant access, accepted answers, Evidence, Investigation Item coverage and outcomes, Interview Session lifecycle, findings, review, retention, deletion, and audit.

## Source scope

This report uses Pi's current official documentation and the official source repository at commit [`c49906e`](https://github.com/earendil-works/pi/tree/c49906ec77788625aacbdc53ebca6fbe65bd20f5), dated 21 August 2026. The repository was formerly reached as `badlogic/pi-mono` and now redirects to `earendil-works/pi`; current package names use the `@earendil-works` scope.

Context7 did not expose the official source repository directly. It resolved Pi's official documentation as `/websites/pi_dev`, which was then checked against the current official repository. No secondary sources are used below.

## Supported integration mechanisms

| Mechanism | Pi support | Fit for one adaptive Interview Session |
| --- | --- | --- |
| SDK in the FindWorks process | `createAgentSession()` creates one `AgentSession`. The application can supply a model, custom `ResourceLoader`, custom tools, skills, settings, persistent or in-memory `SessionManager`, event subscription, and abort handling. [SDK core concepts](https://pi.dev/docs/latest/sdk#core-concepts) | Full capability and best TypeScript ergonomics. No process fault isolation: a Pi, extension, or runtime failure shares the FindWorks process. Suitable if measured process overhead later outweighs the isolation benefit. |
| SDK in a FindWorks-owned worker process | The same SDK can be wrapped by a small worker using a FindWorks-defined protocol. Pi also exports `runRpcMode()`, but this still leaves process supervision and transport to FindWorks. [SDK run modes](https://pi.dev/docs/latest/sdk#run-modes) | Can combine typed customisation with process isolation, but creates a custom worker protocol or duplicates Pi's existing RPC boundary. Add only if the stock RPC contract proves insufficient. |
| `pi --mode rpc` child process | Headless JSONL commands arrive on stdin; responses and asynchronous agent events leave on stdout. Pi documents this mode for embedding in other applications and explicitly names process isolation as a reason to choose it. [RPC mode](https://pi.dev/docs/latest/rpc), [SDK comparison](https://pi.dev/docs/latest/sdk#rpc-mode-alternative) | Best-supported minimal process boundary. One process can be bound to one explicit Pi session and supervised by FindWorks. Raw RPC remains internal to the runtime adapter. |
| Pi hosted service or daemon | No documented Pi HTTP, WebSocket, queue, daemon, or multi-session server API exists. Official programmatic modes are SDK embedding, print/JSON output, and stdin/stdout RPC. [SDK run modes](https://pi.dev/docs/latest/sdk#run-modes) | FindWorks must own the web API, authentication, scheduling, concurrency, supervision, and semantic event stream. A separate runtime service is possible but would be FindWorks code, not a Pi facility. |
| Print or JSON event-stream mode | Print mode is single-shot. JSON mode streams events but does not provide RPC's bidirectional command protocol. Extensions have no interactive UI in either mode. [SDK `runPrintMode`](https://pi.dev/docs/latest/sdk#runprintmode), [extension mode behaviour](https://pi.dev/docs/latest/extensions#mode-behavior) | Poor fit for a resumable, multi-turn interview. Useful for offline extraction jobs or diagnostics, not the live Interview Session. |

## Session isolation and resource loading

RPC accepts an explicit `--session <path|id>` and `--session-dir <dir>`. It can therefore start or restart against one known Pi session rather than selecting the most recent session implicitly. Pi also supports `--no-session`, but that would discard the runtime checkpoint and is a poor fit for interruption recovery. [Pi CLI session options](https://github.com/earendil-works/pi/blob/c49906ec77788625aacbdc53ebca6fbe65bd20f5/packages/coding-agent/README.md#cli-reference)

Pi's CLI can suppress ambient resources with `--no-extensions`, `--no-skills`, `--no-context-files`, and related flags, then explicitly add a trusted extension with `--extension` and a skill with `--skill`. `--no-builtin-tools` keeps extension tools while removing the default file and shell tools; `--tools` can then allowlist exact tool names. These controls provide deterministic runtime composition. They do not by themselves create a security boundary. [Pi CLI tool and resource options](https://github.com/earendil-works/pi/blob/c49906ec77788625aacbdc53ebca6fbe65bd20f5/packages/coding-agent/README.md#cli-reference)

The SDK provides the same control in-process. A custom `DefaultResourceLoader` can override the system prompt, add or replace skills, and load extension factories or explicit extension paths. `createAgentSession()` can disable built-in tools and receive only `customTools`. [SDK resource loading](https://pi.dev/docs/latest/sdk#resourceloader), [SDK tools and custom tools](https://pi.dev/docs/latest/sdk#tools)

Pi discovers skill names and descriptions at startup and places available skills in the system prompt. The model normally reads a matching `SKILL.md` on demand, while `/skill:name` forces expansion. A skill is therefore behavioural instruction, not an enforcement or state boundary. FindWorks should explicitly load only the approved interview skill and should enforce Mission boundaries through application-owned tools and validation. [Pi skills](https://pi.dev/docs/latest/skills#how-skills-work)

## Semantic application tools

Both supported integration paths can expose FindWorks semantic tools:

- The SDK accepts schema-defined tools through `defineTool()` and `customTools`.
- RPC loads a TypeScript extension that registers schema-defined tools through `pi.registerTool()`.
- Tool execution receives a `toolCallId`, parameters, an abort signal, and an optional progress callback. Tool lifecycle events expose the same `toolCallId` for correlation.
- A tool can return `terminate: true` to ask Pi to stop the automatic follow-up model call after the current tool batch, provided every finalised result in that batch is terminating. This supports a turn that ends after a semantic `ask_question` or `complete_interview` action rather than emitting uncontrolled prose afterwards.
- Throwing from a tool marks its result as an error and reports that error to the model. Returning an ordinary result never sets the error flag.

[Pi extension tool definition](https://pi.dev/docs/latest/extensions#tool-definition), [Pi RPC tool events](https://pi.dev/docs/latest/rpc#tool_execution_start--tool_execution_update--tool_execution_end)

Pi executes sibling tool calls concurrently by default. A model can also call a state-changing tool more than once. FindWorks must therefore enforce authorisation, Mission version, legal Interview Session transition, uniqueness, and idempotency inside its application transaction. Tool descriptions and skill instructions are not sufficient. Each mutation should carry a stable FindWorks operation key or be naturally idempotent against the semantic target. A Pi `toolCallId` is useful correlation data, but it should not be the only duplicate guard because a replayed model turn may produce a new tool call.

The extension should call FindWorks application services or a narrow authenticated internal API. Pi should receive only the minimum result needed to continue, for example the current unresolved Investigation Items or confirmation that Evidence was recorded. Raw answer content, credentials, and unrelated Discovery data should not be written into extension logs or tool diagnostics.

## Streaming and the stakeholder-facing contract

The SDK offers `session.subscribe()`. RPC emits JSONL events for agent, turn, message, tool, queue, compaction, retry, and extension lifecycles. `message_update` is a partial provider-facing stream; `message_end` contains the authoritative finalised message. `tool_execution_start`, `tool_execution_update`, and `tool_execution_end` correlate using `toolCallId`. `agent_end` is only the end of one low-level run, while `agent_settled` means no automatic retry, compaction retry, or queued continuation remains. [SDK events](https://pi.dev/docs/latest/sdk#events), [RPC events](https://pi.dev/docs/latest/rpc#events)

These are useful runtime signals but not a stable web contract. FindWorks should terminate the raw stream at its runtime adapter and emit application events derived from committed domain state, such as `question_ready`, `answer_accepted`, `interview_paused`, `interview_completed`, and `runtime_failed`. Model text deltas, thinking deltas, provider payloads, Pi message shapes, and raw tool results should not reach the browser.

For the settled one-question-at-a-time UX, the safest visible boundary is a committed semantic question, not partial assistant prose. Runtime status such as working or retrying can be derived from Pi lifecycle events, but the browser should receive a stable FindWorks status vocabulary. The adapter should treat `agent_settled`, not `agent_end`, as the point at which an otherwise unfinished run has stopped progressing automatically.

## Interruption and resume

Pi persists sessions as JSONL trees. Completed user, assistant, and tool-result messages are session entries, alongside model changes, compaction summaries, and optional extension entries. `SessionManager.open(path)` reopens a specific session; the CLI has `--session <path|id>`; RPC also supports session switching and entry queries. [Session file format](https://pi.dev/docs/latest/session-format), [SessionManager API](https://pi.dev/docs/latest/session-format#sessionmanager-api), [RPC session commands](https://pi.dev/docs/latest/rpc#session)

That persistence is sufficient to restore Pi's conversational context, but it is not an application durability guarantee. Pi persists a finalised message at `message_end`. A hard process failure can occur after a FindWorks tool commits a domain mutation but before Pi records the corresponding tool result. Conversely, partial streamed text is not an accepted FindWorks question or answer.

The Pi session file contains interview content, tool arguments and results, and potentially model thinking. It is therefore sensitive runtime data within the Interview Session's access, retention, and deletion boundary. It must use controlled storage, must never be treated as a content-free audit record, and must be purged with the Interview Session or Discovery. Process stdout and stderr need the same content-safe handling because RPC carries raw events over stdout.

The safe recovery order is therefore:

1. FindWorks commits an interviewee answer as immutable Evidence before sending that accepted answer to Pi.
2. Semantic tools commit through FindWorks transactions with idempotency and legal state-transition checks.
3. The runtime adapter records the associated Pi session path or ID as secondary runtime metadata.
4. After a process or host interruption, the supervisor opens the same Pi session and reconciles it against authoritative FindWorks state.
5. If the checkpoint and product state disagree, FindWorks state wins and the adapter supplies the missing committed facts to the runtime rather than reversing or duplicating them.

This satisfies the settled requirement that an interrupted Interview Session resumes after its last accepted answer. It does not require Pi to own Evidence or Interview Session status.

## Model and tool failure recovery

Pi supports aborting the current agent operation through both SDK and RPC. It automatically retries transient provider failures such as overloads, rate limits, and 5xx responses when retry is enabled, with `auto_retry_start` and `auto_retry_end` events. Context-overflow compaction can also retry the interrupted prompt. `agent_settled` marks the end of all automatic continuation. [RPC abort](https://pi.dev/docs/latest/rpc#abort), [RPC retry events](https://pi.dev/docs/latest/rpc#auto_retry_start--auto_retry_end), [Pi agent-session retry source](https://github.com/earendil-works/pi/blob/c49906ec77788625aacbdc53ebca6fbe65bd20f5/packages/coding-agent/src/core/agent-session.ts#L2808-L2861)

Tool failures are different. Pi catches a thrown tool error, reports an error result to the model, and continues the agent loop. Pi does not document an application-level exactly-once guarantee or automatic retry contract for state-changing tools. Extension event failures are reported and the agent generally continues. [Extension error handling](https://pi.dev/docs/latest/extensions#error-handling)

FindWorks should classify failures at the adapter boundary:

- A transient model failure may use Pi's bounded retry and remain a runtime status only.
- An exhausted model failure becomes a recoverable `runtime_failed` state without changing the last accepted Evidence or Interview Session completion state.
- A rejected or failed semantic tool must not advance FindWorks state. The tool can return enough safe information for the model to correct a validation error, or the adapter can stop the run for operator retry.
- A process crash is recovered by a supervised restart against the same Pi checkpoint plus authoritative FindWorks state.
- A findings-extraction failure is a separate post-session job and can rerun solely from FindWorks Evidence, so it need not reopen the Interview Session or depend on a live Pi interview process.

## Candidate M0 runtime shape

The research supports, but does not itself settle, this candidate:

```text
Interviewee web client
  -> FindWorks application and relational store
       -> stable application event stream
       -> Pi runtime supervisor
            -> one RPC process per active Interview Session
                 -> approved interview skill only
                 -> trusted FindWorks semantic-tool extension only
                 -> selected model provider
```

The process can remain alive while an interview is actively exchanging answers, or be stopped while waiting and reopened from its explicit session file. That lifecycle choice should be measured in the prototype because Pi documents both persistence and process integration but does not prescribe a web-session hosting policy.

The smallest proof should exercise these risks before the architecture is locked:

1. Produce exactly one committed question through a terminating semantic tool and map it to a stable FindWorks event.
2. Accept an answer into FindWorks Evidence, feed it to Pi, and produce an answer-dependent next question.
3. Kill the RPC process after a state-changing tool commits but before its result is safely observed, then prove restart and reconciliation do not duplicate state.
4. Trigger a transient model retry, an exhausted model failure, a tool validation failure, and an abort.
5. Verify that no built-in file or shell tool, ambient skill, context file, or unapproved extension is available.
6. Run two Interview Sessions concurrently and prove their Pi sessions, scoped credentials, Mission context, semantic tool effects, and outgoing application events cannot cross.

## Decision input

Pi provides enough supported surface for FindWorks M0 without making Pi authoritative product state. Choose between these two viable options in the architecture ticket:

- **RPC child process per active Interview Session:** strongest fault isolation and clearest session ownership, with a small JSONL adapter and process supervisor.
- **SDK `AgentSession` in a FindWorks-owned worker:** strongest type safety and direct customisation, with weaker fault isolation unless the worker itself is process-isolated.

Do not create a custom Pi service, custom protocol, or long-running multi-session daemon unless the RPC prototype exposes a concrete missing capability. Do not use print or JSON mode for the live interview. Regardless of mechanism, keep all FindWorks truth in the application store, expose only semantic tools, translate raw Pi events before the web boundary, and make every state-changing operation replay-safe.
