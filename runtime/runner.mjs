import { spawn } from "node:child_process";
import { writeFile } from "node:fs/promises";
import { preparePiEnvironment } from "./auth.mjs";
import { prepareInterviewContext } from "./interview-strategy.mjs";
import { JsonlDecoder, RpcTurnMonitor } from "./rpc-state.mjs";

const authoritativeContext = await readContext(process.stdin);
const contextPath = "/tmp/interview-context.json";
await writeFile(contextPath, JSON.stringify(authoritativeContext), { mode: 0o600 });
const context = JSON.stringify(prepareInterviewContext(authoritativeContext));
const piEnv = { ...preparePiEnvironment(), FINDWORKS_INTERVIEW_CONTEXT: contextPath };
const monitor = new RpcTurnMonitor("submit_interview_turn", {
  reasoningEffort: process.env.FINDWORKS_REASONING_EFFORT || "low",
});
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
  "--system-prompt", "Conduct one bounded FindWorks interview turn using the skill's dependency-aware design-tree grilling method. Treat conversation_state.continuity as authoritative working memory. Stay on current_thread_result_id until supported or honestly unresolved; ask one respectful non-leading question at a time, with one information need. Do not append a second request to an example question. Every accepted answer advances the conversation: never repeat or paraphrase an attempted_information_need, even when its answer was indirect; choose a different unresolved need or close honestly. Reuse at least one exact, natural domain phrase from latest_interviewee_wording in each same-thread Question. Never reveal Mission planning language, invent possible answers, or use a structured response unless it is genuinely finite and grounded. For an extended supported thread, first gather every exit condition, then require one distinct accepted closure or synthesis answer; transition or complete only after that answer. A terminal outcome must collectively link every Evidence identity in continuity.current_thread.evidence_ids, including corrections and the closure answer. Treat outcomes as a delta against current_outcomes; never restate an unchanged claim. Do not emit terminal coverage for partial support or while continuing that result. Move only to the next required open result, and complete only when every required result is terminal. Submit one semantic turn. If the local strategy or server rejects it, repair every reported code in one corrected submission. Never write prose outside tool calls.",
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
    if (records.length > 0) return records[0];
  }
  const records = decoder.end();
  if (records.length > 0) return records[0];
  throw new Error("Missing runtime context");
}

function positiveInteger(value, fallback) {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : fallback;
}
