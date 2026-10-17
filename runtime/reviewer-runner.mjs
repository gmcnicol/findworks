import { spawn } from "node:child_process";
import { preparePiEnvironment } from "./auth.mjs";
import { JsonlDecoder, RpcTurnMonitor } from "./rpc-state.mjs";

const request = await readRequest(process.stdin);
const monitor = new RpcTurnMonitor("submit_investigation_review");
const decoder = new JsonlDecoder();
const deadlineMs = positiveInteger(process.env.FINDWORKS_RUNNER_DEADLINE_MS, 105_000);

const pi = spawn("pi", [
  "--mode", "rpc",
  "--provider", process.env.FINDWORKS_MODEL_PROVIDER || "openai",
  "--model", process.env.FINDWORKS_MODEL || "gpt-5.4-mini",
  "--thinking", process.env.FINDWORKS_REASONING_EFFORT || "low",
  "--session-dir", "/tmp/session",
  "--no-builtin-tools",
  "--tools", "submit_investigation_review",
  "--no-extensions", "--no-skills", "--no-context-files", "--no-prompt-templates", "--no-themes",
  "--offline",
  "--extension", "/runtime/reviewer-extension.ts",
  "--skill", "/runtime/reviewer/SKILL.md",
  "--system-prompt", "Act only as the FindWorks investigation reviewer. Inspect the supplied approved Mission, immutable Evidence, unresolved required coverage, conflicts, sufficiency, and next-thread priority. Return one structured advisory proposal through submit_investigation_review. Never ask an interview Question, invent facts, rewrite Evidence, change the Mission, or claim authority over Session state. Copy every request identity and revision exactly, use proposal-<requestId>, and emit no prose. Compute completion after projecting outcomeProposals: only UNADDRESSED, EXPLORING, and PARTIAL required results are open; CONFLICT and all other terminal outcomes count as complete; list every and only projected-open required result identity, and use no next thread when READY.",
], { stdio: ["pipe", "pipe", "pipe"], env: preparePiEnvironment() });

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
  const usage = monitor.usage();
  if (usage) process.stdout.write(`${JSON.stringify({ findworksRuntimeMetrics: usage })}\n`);
  process.exitCode = outcome ?? (code === 0 ? 70 : code || 70);
});

pi.stdin.write(`${JSON.stringify({ id: "retry-policy", type: "set_auto_retry", enabled: false })}\n`);
pi.stdin.write(`${JSON.stringify({ id: "review", type: "prompt", message: `/skill:findworks-investigation-review Review this authoritative request and submit one proposal:\n${request}` })}\n`);

function finish(exitCode) {
  if (outcome !== undefined) return;
  outcome = exitCode;
  clearTimeout(deadline);
  pi.kill("SIGTERM");
  const kill = setTimeout(() => pi.kill("SIGKILL"), 2_000);
  kill.unref();
}

async function readRequest(input) {
  const inputDecoder = new JsonlDecoder();
  for await (const chunk of input) {
    const records = inputDecoder.push(chunk);
    if (records.length > 0) return JSON.stringify(records[0]);
  }
  const records = inputDecoder.end();
  if (records.length > 0) return JSON.stringify(records[0]);
  throw new Error("Missing reviewer request");
}

function positiveInteger(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}
