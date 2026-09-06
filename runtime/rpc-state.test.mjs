import assert from "node:assert/strict";
import test from "node:test";
import { JsonlDecoder, RpcTurnMonitor } from "./rpc-state.mjs";

test("JSONL decoder splits only on LF and preserves Unicode separators", () => {
  const decoder = new JsonlDecoder();
  const records = decoder.push(Buffer.from('{"value":"before\u2028after"}\n{"value":2}\n'));
  assert.deepEqual(records, [{ value: "before\u2028after" }, { value: 2 }]);
});

test("retry-policy rejection terminates before starting an uncontrolled nested retry loop", () => {
  const monitor = new RpcTurnMonitor();
  assert.deepEqual(monitor.observe({ type: "response", command: "set_auto_retry", success: false }), {
    terminal: true,
    exitCode: 64,
    reason: "RETRY_POLICY_REJECTED",
  });
});

test("prompt rejection terminates immediately instead of waiting for agent_settled", () => {
  const monitor = new RpcTurnMonitor();
  assert.deepEqual(monitor.observe({ type: "response", command: "prompt", success: false }), {
    terminal: true,
    exitCode: 64,
    reason: "PROMPT_REJECTED",
  });
});

test("a settled run succeeds only after the semantic tool commits", () => {
  const monitor = new RpcTurnMonitor();
  assert.equal(monitor.observe({ type: "response", command: "prompt", success: true }).terminal, false);
  assert.equal(monitor.observe({ type: "tool_execution_end", toolName: "submit_interview_turn", isError: false }).terminal, false);
  assert.deepEqual(monitor.observe({ type: "agent_settled" }), {
    terminal: true,
    exitCode: 0,
    reason: "COMMITTED",
  });
});

test("a provider error settles as retryable without a semantic commit", () => {
  const monitor = new RpcTurnMonitor();
  monitor.observe({
    type: "message_end",
    message: { role: "assistant", stopReason: "error", errorMessage: "provider unavailable" },
  });
  assert.deepEqual(monitor.observe({ type: "agent_settled" }), {
    terminal: true,
    exitCode: 75,
    reason: "PROVIDER_ERROR",
  });
});

test("a settled run without a tool commit is not reported as successful", () => {
  const monitor = new RpcTurnMonitor();
  assert.deepEqual(monitor.observe({ type: "agent_settled" }), {
    terminal: true,
    exitCode: 70,
    reason: "NO_SEMANTIC_COMMIT",
  });
});
