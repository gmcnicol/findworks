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
  "--thinking", process.env.FINDWORKS_REASONING_EFFORT || "low",
  "--session-dir", "/tmp/session",
  "--no-builtin-tools",
  "--tools", "submit_interview_turn",
  "--no-extensions", "--no-skills", "--no-context-files", "--no-prompt-templates", "--no-themes",
  "--offline",
  "--extension", "/runtime/extension.ts",
  "--skill", "/runtime/SKILL.md",
  "--system-prompt", "Conduct one bounded FindWorks interview turn using the skill's dependency-aware design-tree grilling method. Continue conversation_state.current_thread_result_id until that thread is sufficiently understood or honestly unresolved; ask one respectful non-leading question at a time, with one information need. Do not append a second request to an example question. Treat Mission plans as private guidance: never recite lists or planning language. Start from Mission domain terms, then make the interviewee's own wording the default; lightly anchor useful follow-ups in their latest words. Do not repeat answered ground or invent possible answers inside a free-text question. Use FREE_TEXT unless the answer is genuinely finite or confirms a paraphrase with alternatives the interviewee already established; phrase any options in the interviewee's words. Treat submitted outcomes as a delta against current_outcomes; never restate an unchanged claim. When asking a follow-up, do not submit a terminal outcome for that result. Every ASK_QUESTION must include the current thread's exact resultId. Submit one semantic turn. If the server returns a correctable rejection code, repair only that proposal and submit once more. Never expose internal terms or write prose outside tool calls.",
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
  const usage = monitor.usage();
  if (usage) process.stdout.write(`${JSON.stringify({ findworksRuntimeMetrics: usage })}\n`);
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
