#!/usr/bin/env node
// THROWAWAY PROTOTYPE. Proves issue 13 against a real Pi RPC child.
import { appendFileSync, mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { spawn } from "node:child_process";
import { performance } from "node:perf_hooks";

const root = mkdtempSync(join(tmpdir(), "findworks-pi-boundary-"));
const extension = resolve(new URL("extension.ts", import.meta.url).pathname);
const timeoutMs = 90_000;

const assert = (condition, message) => {
  if (!condition) throw new Error(message);
};

const records = (file) => {
  try {
    return readFileSync(file, "utf8").trim().split("\n").filter(Boolean).map(JSON.parse);
  } catch (error) {
    if (error.code === "ENOENT") return [];
    throw error;
  }
};

const append = (file, value) => appendFileSync(file, `${JSON.stringify(value)}\n`);

const questionEvents = (file) => {
  const seen = new Set();
  return records(file)
    .filter((record) => record.kind === "question_commit")
    .filter((record) => !seen.has(record.operationKey) && seen.add(record.operationKey))
    .map((record) => ({
      type: "question_ready",
      eventId: `question:${record.operationKey}`,
      sessionId: record.sessionId,
      question: record.question,
    }));
};

class RpcChild {
  constructor({ name, store, mission, credential, crash }) {
    this.events = [];
    this.waiters = [];
    this.stderr = "";
    const sessionDir = join(root, `${name}-sessions`);
    const args = [
      "--mode", "rpc",
      "--provider", "openai-codex",
      "--model", "gpt-5.6-luna",
      "--thinking", "off",
      "--session-dir", sessionDir,
      "--session-id", name,
      "--no-builtin-tools",
      "--tools", "ask_question,reject_invalid_effect",
      "--no-extensions",
      "--no-skills",
      "--no-context-files",
      "--no-prompt-templates",
      "--no-themes",
      "--offline",
      "--extension", extension,
      "--system-prompt",
      "You conduct one interview. Obey each user instruction exactly. Call only the requested tool, exactly once, with exact scope values. Never use technical language in questions. Do not write prose before or after a tool call.",
    ];
    const env = {
      HOME: process.env.HOME,
      PATH: process.env.PATH,
      USER: process.env.USER,
      SHELL: process.env.SHELL,
      FINDWORKS_PROTOTYPE_STORE: store,
      FINDWORKS_SESSION_ID: name,
      FINDWORKS_MISSION_MARKER: mission,
      FINDWORKS_CREDENTIAL_MARKER: credential,
    };
    if (crash) env.FINDWORKS_CRASH_AFTER_COMMIT = crash;
    this.process = spawn("pi", args, { env, stdio: ["pipe", "pipe", "pipe"] });
    this.process.stderr.on("data", (chunk) => { this.stderr += chunk; });
    let buffer = "";
    this.process.stdout.on("data", (chunk) => {
      buffer += chunk;
      for (;;) {
        const split = buffer.indexOf("\n");
        if (split < 0) break;
        const line = buffer.slice(0, split).replace(/\r$/, "");
        buffer = buffer.slice(split + 1);
        if (!line) continue;
        this.events.push(JSON.parse(line));
        for (const waiter of [...this.waiters]) {
          if (waiter.predicate(this.events.at(-1))) waiter.resolve(this.events.at(-1));
        }
      }
    });
    this.exited = new Promise((resolveExit) => this.process.once("exit", (code, signal) => resolveExit({ code, signal })));
  }

  send(value) {
    this.process.stdin.write(`${JSON.stringify(value)}\n`);
  }

  wait(predicate, label, timeout = timeoutMs) {
    const existing = this.events.findLast(predicate);
    if (existing) return Promise.resolve(existing);
    return new Promise((resolveWait, reject) => {
      const waiter = {
        predicate,
        resolve: (event) => {
          clearTimeout(timer);
          this.waiters.splice(this.waiters.indexOf(waiter), 1);
          resolveWait(event);
        },
      };
      const timer = setTimeout(() => {
        this.waiters.splice(this.waiters.indexOf(waiter), 1);
        reject(new Error(`${label} timed out. ${this.stderr.slice(-500)}`));
      }, timeout);
      this.waiters.push(waiter);
    });
  }

  async prompt(message, id = crypto.randomUUID()) {
    const priorSettled = this.events.filter((event) => event.type === "agent_settled").length;
    this.send({ id, type: "prompt", message });
    await this.wait((event) => event.type === "response" && event.id === id, `prompt ${id}`);
    await this.wait(
      () => this.events.filter((event) => event.type === "agent_settled").length > priorSettled,
      `agent_settled ${id}`,
    );
  }

  async state(id = crypto.randomUUID()) {
    this.send({ id, type: "get_state" });
    return this.wait((event) => event.type === "response" && event.id === id, "get_state");
  }

  async stop() {
    if (this.process.exitCode !== null) return;
    this.process.kill("SIGTERM");
    await this.exited;
  }
}

const ask = (operationKey, sessionId, missionMarker, instruction) =>
  `Call ask_question exactly once with operationKey ${JSON.stringify(operationKey)}, sessionId ${JSON.stringify(sessionId)}, missionMarker ${JSON.stringify(missionMarker)}. ${instruction}`;

const runtimeState = (events) => events.reduce((state, event) => {
  if (event.type === "auto_retry_start") return { status: "retrying", recoverable: true };
  if (event.type === "auto_retry_end") return { status: event.success ? "running" : "failed", recoverable: true };
  if (event.type === "tool_execution_end" && event.isError) return { status: "tool_failed", recoverable: true };
  if (event.type === "agent_settled") return { status: "ready", recoverable: true };
  return state;
}, { status: "ready", recoverable: true });

async function main() {
  const result = { piVersion: "0.84.2", scratchDirectory: root, checks: {}, limitations: [] };
  const mainStore = join(root, "main.jsonl");
  const child = new RpcChild({ name: "session-main", store: mainStore, mission: "mission-main", credential: "credential-main" });

  await child.prompt(ask("opening", "session-main", "mission-main", "Ask: What happens after a customer reports a failed payment?"));
  let events = questionEvents(mainStore);
  assert(events.length === 1 && events[0].eventId === "question:opening", "one commit did not map to one stable event");
  result.checks.stableSemanticEvent = { pass: true, event: events[0] };

  append(mainStore, { kind: "evidence", evidenceId: "evidence-1", answer: "Support retries the payment manually, then emails the customer." });
  await child.prompt(ask("follow-up", "session-main", "mission-main", "Ask a follow-up that explicitly says Support and depends on this accepted answer: Support retries the payment manually, then emails the customer."));
  events = questionEvents(mainStore);
  const followUp = events.find((event) => event.eventId === "question:follow-up");
  assert(followUp?.question.includes("Support"), "next question did not depend on accepted Evidence");
  result.checks.answerDependentQuestion = { pass: true, question: followUp.question };

  const idleStart = performance.now();
  await new Promise((resolveWait) => setTimeout(resolveWait, 250));
  const idleState = await child.state("idle-state");
  result.checks.idleChild = { pass: idleState.success && idleState.data.isStreaming === false, observedMs: Math.round(performance.now() - idleStart) };

  const invalidBefore = questionEvents(mainStore).length;
  await child.prompt("Call reject_invalid_effect exactly once with reason \"bad scope\". Do not call another tool.", "invalid-tool");
  assert(questionEvents(mainStore).length === invalidBefore, "failed semantic tool changed application state");
  const invalidEvent = child.events.find((event) => event.type === "tool_execution_end" && event.isError);
  assert(invalidEvent, "Pi did not expose semantic-tool failure");
  result.checks.toolFailureRecovery = { pass: runtimeState([invalidEvent]).recoverable };

  const settledBeforeAbort = child.events.filter((event) => event.type === "agent_settled").length;
  child.send({ id: "abort-prompt", type: "prompt", message: "Explain every prime number below one million without tools." });
  await child.wait((event) => event.type === "response" && event.id === "abort-prompt", "abort prompt");
  child.send({ id: "abort", type: "abort" });
  const abortResponse = await child.wait((event) => event.type === "response" && event.id === "abort", "abort response");
  await child.wait(() => child.events.filter((event) => event.type === "agent_settled").length > settledBeforeAbort, "abort settled");
  const stateAfterAbort = await child.state("after-abort");
  assert(abortResponse.success && !stateAfterAbort.data.isStreaming, "abort did not return child to recoverable state");
  result.checks.abortRecovery = { pass: true };

  const audit = records(mainStore).find((record) => record.kind === "resource_audit");
  assert(JSON.stringify(audit.activeTools) === JSON.stringify(["ask_question", "reject_invalid_effect"]), "unexpected active tools");
  result.checks.ambientResourcesDisabled = { pass: true, activeTools: audit.activeTools, launchFlags: ["--no-builtin-tools", "--no-extensions", "--no-skills", "--no-context-files", "--no-prompt-templates", "--no-themes"] };
  await child.stop();

  const reopenStart = performance.now();
  const reopened = new RpcChild({ name: "session-main", store: mainStore, mission: "mission-main", credential: "credential-main" });
  await reopened.state("reopened-state");
  result.checks.stopAndReopen = { pass: true, observedMs: Math.round(performance.now() - reopenStart) };
  await reopened.stop();

  const crashStore = join(root, "crash.jsonl");
  const doomed = new RpcChild({ name: "session-crash", store: crashStore, mission: "mission-crash", credential: "credential-crash", crash: "crash-question" });
  doomed.send({ id: "crash", type: "prompt", message: ask("crash-question", "session-crash", "mission-crash", "Ask: Who approves a refund?") });
  const death = await new Promise((resolveDeath, reject) => {
    const timer = setTimeout(() => reject(new Error("crash child did not die")), timeoutMs);
    doomed.exited.then((value) => {
      clearTimeout(timer);
      resolveDeath(value);
    });
  });
  assert(death.signal === "SIGKILL", "crash was not injected after commit");
  const survivor = new RpcChild({ name: "session-crash", store: crashStore, mission: "mission-crash", credential: "credential-crash" });
  await survivor.prompt(ask("crash-question", "session-crash", "mission-crash", "Ask: Who approves a refund?"), "replay");
  const recovered = questionEvents(crashStore);
  assert(recovered.length === 1, "restart duplicated committed question");
  result.checks.crashAfterCommit = { pass: true, event: recovered[0] };
  await survivor.stop();

  const scoped = [
    { name: "session-a", mission: "mission-a", credential: "credential-a", store: join(root, "a.jsonl") },
    { name: "session-b", mission: "mission-b", credential: "credential-b", store: join(root, "b.jsonl") },
  ];
  const children = scoped.map((scope) => new RpcChild(scope));
  await Promise.all(children.map((rpc, index) => rpc.prompt(ask(`question-${index}`, scoped[index].name, scoped[index].mission, `Ask: What is step ${index + 1}?`))));
  scoped.forEach((scope, index) => {
    const commits = records(scope.store).filter((record) => record.kind === "question_commit");
    assert(commits.length === 1, `${scope.name} wrong effect count`);
    assert(commits[0].sessionId === scope.name && commits[0].missionMarker === scope.mission && commits[0].credentialMarker === scope.credential, `${scope.name} crossed scope`);
    assert(!records(scoped[1 - index].store).some((record) => record.sessionId === scope.name), `${scope.name} crossed store`);
  });
  result.checks.concurrentSessionScope = { pass: true, sessions: scoped.map(({ name, mission, credential }) => ({ name, mission, credential })) };
  await Promise.all(children.map((rpc) => rpc.stop()));

  const transient = runtimeState([{ type: "auto_retry_start" }, { type: "auto_retry_end", success: true }, { type: "agent_settled" }]);
  const exhausted = runtimeState([{ type: "auto_retry_start" }, { type: "auto_retry_end", success: false }]);
  assert(transient.status === "ready" && exhausted.status === "failed" && exhausted.recoverable, "runtime adapter mishandled retry events");
  result.checks.retryProtocol = { pass: true, scope: "adapter replay of documented Pi RPC events; provider fault not injected", transient, exhausted };

  result.limitations.push("Pi child and trusted extension retain host filesystem/process access. Use an OS account, sandbox, or container for hard isolation.");
  result.limitations.push("Provider credentials are read from Pi's host credential store. Per-session provider credential isolation needs separate credential stores or containers.");
  result.limitations.push("Transient and exhausted provider retries were checked at adapter protocol level, not induced against the live provider.");
  console.log(JSON.stringify(result, null, 2));
}

main().catch((error) => {
  console.error(error.stack || error);
  process.exitCode = 1;
});
