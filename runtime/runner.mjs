import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { preparePiEnvironment } from "./auth.mjs";

const input = createInterface({ input: process.stdin, crlfDelay: Infinity });
const context = await new Promise((resolve) => input.once("line", resolve));
input.close();

const piEnv = preparePiEnvironment();

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
  "--system-prompt", "Conduct one bounded FindWorks interview turn. Use only supplied context. Call submit_interview_turn exactly once. Never expose internal terms or write prose outside the tool call.",
], { stdio: ["pipe", "pipe", "pipe"], env: piEnv });

let settled = false;
const lines = createInterface({ input: pi.stdout, crlfDelay: Infinity });
lines.on("line", (line) => {
  let event;
  try { event = JSON.parse(line); } catch { return; }
  if (event.type === "agent_settled") {
    settled = true;
    pi.kill("SIGTERM");
  }
});
pi.stderr.resume();
pi.stdin.write(`${JSON.stringify({ id: "turn", type: "prompt", message: `Follow the interview skill and complete one turn from this authoritative JSON context:\n${context}` })}\n`);

const exit = await new Promise((resolve) => pi.once("exit", resolve));
process.exitCode = settled ? 0 : (exit || 1);
