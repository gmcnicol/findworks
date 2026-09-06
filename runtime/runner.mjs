import { spawn } from "node:child_process";
import { preparePiEnvironment } from "./auth.mjs";
import { JsonlDecoder, RpcTurnMonitor } from "./rpc-state.mjs";

const context = await readContext(process.stdin);
const piEnv = preparePiEnvironment();
const monitor = new RpcTurnMonitor();
const decoder = new JsonlDecoder();
const deadlineMs = positiveInteger(process.env.FINDWORKS_RUNNER_DEADLINE_MS, 105_000);

const pi = spawn("pi", [
  "--mode", "rpc",
  "--provider", process.env.FINDWORKS_MODEL_PROVIDER || "openai",
  "--model", process.env.FINDWORKS_MODEL || "gpt-5.4-mini",
  "--thinking", "off",
  "--session-dir", "/tmp/session",
  "--no-builtin-tools",
  "--tools", "submit_interview_turn",
  "--no-extensions", "--no-skills", "--no-context-files", "--no-prompt-templates", "--no-themes",
  "--offline",
  "--extension", "/runtime/extension.ts",
  "--skill", "/runtime/SKILL.md",
  "--system-prompt", "Conduct one bounded FindWorks interview turn using the skill's dependency-aware design-tree grilling method. Recompute the unresolved frontier from explicit Evidence, test the highest-value ready branch against its sufficient-evidence criteria, and ask one respectful non-leading question at a time. Every ASK_QUESTION must include that branch's exact resultId and question text; do not close a branch you are still probing. Submit one semantic turn. If the server returns a correctable rejection code, repair that proposal and submit once more. Never expose internal terms or write prose outside tool calls.",
], { stdio: ["pipe", "pipe", "pipe"], env: piEnv });

let outcome;
const deadline = setTimeout(() => finish(124), deadlineMs);
deadline.unref();

pi.stdout.on("data", chunk => {
  try {
    for (const event of decoder.push(chunk)) {
      const observed = monitor.observe(event);
      if (observed.terminal) finish(observed.exitCode);
    }
  } catch {
    finish(65);
  }
});
pi.stdout.on("end", () => {
  try {
    for (const event of decoder.end()) {
      const observed = monitor.observe(event);
      if (observed.terminal) finish(observed.exitCode);
    }
  } catch {
    finish(65);
  }
});
pi.stderr.resume();
pi.on("error", () => finish(70));
pi.on("exit", code => {
  clearTimeout(deadline);
  process.exitCode = outcome ?? (code === 0 ? 70 : code || 70);
});

// The worker owns the three-attempt retry budget and backoff. Disable Pi's nested
// retry loop so one infrastructure attempt has one bounded provider call.
pi.stdin.write(`${JSON.stringify({ id: "retry-policy", type: "set_auto_retry", enabled: false })}\n`);
pi.stdin.write(`${JSON.stringify({ id: "turn", type: "prompt", message: `/skill:findworks-interview Complete one bounded turn from this authoritative JSON context:\n${context}` })}\n`);

function finish(exitCode) {
  if (outcome !== undefined) return;
  outcome = exitCode;
  clearTimeout(deadline);
  pi.kill("SIGTERM");
  const kill = setTimeout(() => pi.kill("SIGKILL"), 2_000);
  kill.unref();
}

async function readContext(input) {
  const decoder = new JsonlDecoder();
  for await (const chunk of input) {
    const records = decoder.push(chunk);
    if (records.length > 0) return JSON.stringify(records[0]);
  }
  const records = decoder.end();
  if (records.length > 0) return JSON.stringify(records[0]);
  throw new Error("Missing runtime context");
}

function positiveInteger(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}
