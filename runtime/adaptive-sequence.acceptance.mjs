import assert from "node:assert/strict";
import { createServer } from "node:http";
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";

const image = process.env.FINDWORKS_RUNTIME_IMAGE;
if (!image) throw new Error("Set FINDWORKS_RUNTIME_IMAGE to the runtime image under test");

const hostContainer = process.env.FINDWORKS_DOCKER_HOST_CONTAINER;
const model = process.env.FINDWORKS_MODEL || "gpt-5.4-mini";
const provider = process.env.FINDWORKS_MODEL_PROVIDER || "openai";
const resultId = "10000000-0000-0000-0000-000000000001";
const runId = "20000000-0000-0000-0000-000000000001";
const sessionId = "30000000-0000-0000-0000-000000000001";

const answers = [
  {
    text: "We call it the morning sort. Maya, the practice manager, looks at the red jobs first because those clients are waiting for payroll.",
    anchors: ["morning sort", "maya", "practice manager", "red jobs", "payroll"],
  },
  {
    text: "A red job means payroll is due today and the bank feed still has a gap. Maya checks the cashbook, then calls it ready when the gap is explained.",
    anchors: ["red job", "payroll", "bank feed", "cashbook", "ready", "maya"],
  },
  {
    text: "If the cashbook still does not explain it, Maya puts it in the help pile for Jo, our payroll lead. Jo decides whether to contact the client.",
    anchors: ["cashbook", "maya", "help pile", "jo", "payroll lead", "client"],
  },
];

const context = {
  run_id: runId,
  session_id: sessionId,
  mission_version: 1,
  expected_revision: 2,
  mission: {
    objective: "Determine the exception-triage architecture for imported transaction reconciliation.",
    desired_outcome: "A decision matrix for prioritisation, escalation, and terminal system states.",
    data_use: "The Investigator will use reviewed findings in project work.",
  },
  shared_mission_entries: [
    { kind: "TERM", position: 0, value: "Mismatch", detail: "An imported transaction that does not reconcile automatically." },
    { kind: "COMPLETION_CRITERION", position: 0, value: "Every exception class has an owner, transition rule, and terminal state." },
  ],
  investigation_results: [{
    result_id: resultId,
    knowledge_gap: "Define the decisioning and escalation architecture for reconciliation exceptions.",
    why_it_matters: "The implementation requires deterministic state transitions.",
    priority: "HIGH",
    required: true,
    relevant_context: "A practice manager reviews imported transaction mismatches.",
    sufficient_evidence: "A decision matrix covering priority, ownership, escalation triggers, exceptions, and completion states.",
    coverage: "EXPLORING",
    current_outcomes: [],
  }],
  questions_and_evidence: [],
  conversation_state: {},
};

let resolveSubmission;
const server = createServer(async (request, response) => {
  if (request.method !== "POST" || request.url !== "/turn") {
    response.writeHead(404).end();
    return;
  }
  let body = "";
  for await (const chunk of request) body += chunk;
  const submission = JSON.parse(body);
  response.writeHead(200, { "Content-Type": "application/json" });
  response.end(JSON.stringify({ event: "question_ready" }));
  resolveSubmission?.(submission);
});
await new Promise(resolve => server.listen(0, "0.0.0.0", resolve));
const port = server.address().port;

try {
  const questions = [];
  for (let turn = 0; turn < answers.length; turn++) {
    const answer = answers[turn];
    const questionId = randomUUID();
    const evidenceId = randomUUID();
    const priorQuestion = turn === 0
      ? "Walk me through the last imported transaction problem you dealt with."
      : questions.at(-1);
    context.questions_and_evidence.push({
      question_id: questionId,
      sequence: turn + 1,
      question: priorQuestion,
      active: false,
      evidence_id: evidenceId,
      source_type: "ANSWER",
      exact_text: answer.text,
    });
    context.conversation_state = {
      current_thread_result_id: resultId,
      last_question: priorQuestion,
      latest_interviewee_wording: answer.text,
    };
    context.expected_revision = turn + 2;

    const submissionPromise = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("Runtime did not submit a turn")), 120_000);
      resolveSubmission = value => {
        clearTimeout(timeout);
        resolve(value);
      };
    });
    const exitPromise = runRuntime(context, port);
    const submission = await submissionPromise;
    await exitPromise;
    resolveSubmission = undefined;

    const action = submission.nextAction;
    assert.equal(action.type, "ASK_QUESTION", `turn ${turn + 1} must continue drilling`);
    assert.equal(action.resultId, resultId, `turn ${turn + 1} switched away from the current thread`);
    assert.ok(!action.responseMode || action.responseMode === "FREE_TEXT",
      `turn ${turn + 1} used ${action.responseMode} instead of own words`);
    assertQuestion(action.text, answer.text, answer.anchors, questions, turn + 1);
    questions.push(action.text);

    for (const outcome of submission.outcomes ?? []) {
      if (!context.investigation_results[0].current_outcomes.some(existing =>
        existing.coverage === outcome.coverage && existing.category === outcome.category && existing.summary === outcome.summary)) {
        context.investigation_results[0].current_outcomes.push({
          outcome_id: randomUUID(),
          coverage: outcome.coverage,
          category: outcome.category,
          summary: outcome.summary,
          evidence_ids: outcome.evidenceIds,
        });
      }
    }
  }

  process.stdout.write(`${JSON.stringify({ model: `${provider}/${model}`, questions }, null, 2)}\n`);
} finally {
  server.close();
}

function assertQuestion(question, latestAnswer, anchors, previousQuestions, turn) {
  const lower = question.toLowerCase();
  const answer = latestAnswer.toLowerCase();
  assert.equal((question.match(/\?/g) ?? []).length, 1, `turn ${turn} must ask exactly one question: ${question}`);
  assert.ok(question.length <= 280, `turn ${turn} is too long: ${question}`);
  assert.ok(anchors.some(anchor => lower.includes(anchor)),
    `turn ${turn} did not adopt the interviewee's wording (${anchors.join(", ")}): ${question}`);
  assert.doesNotMatch(lower, /investigation item|sufficient evidence|decision matrix|system state|architecture|capability inventory/,
    `turn ${turn} exposed Mission planning language: ${question}`);
  assert.doesNotMatch(lower, /\b(?:and|or)\s+(?:what|who|when|where|why|how|does|do|is|are|should|would|can)\b/,
    `turn ${turn} bundled another information need: ${question}`);
  for (const leadingFrame of ["rather than", "instead of"]) {
    assert.ok(!lower.includes(leadingFrame) || answer.includes(leadingFrame),
      `turn ${turn} invented alternatives inside the question: ${question}`);
  }
  assert.ok(!previousQuestions.some(previous => normalise(previous) === normalise(question)),
    `turn ${turn} repeated an earlier question: ${question}`);
}

function normalise(value) {
  return value.toLowerCase().replace(/[^a-z0-9 ]/g, "").replace(/\s+/g, " ").trim();
}

function runRuntime(context, port) {
  const dockerArgs = [
    "run", "--rm", "-i", "--read-only", "--add-host", "host.docker.internal:host-gateway",
    "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m", "--cap-drop", "ALL",
    "--security-opt", "no-new-privileges", "--pids-limit", "64", "--memory", "512m", "--cpus", "0.5",
    "--user", "10001:10001", "--env", "OPENAI_API_KEY", "--env", "FINDWORKS_MODEL_OAUTH_B64",
    "--env", `FINDWORKS_MODEL_PROVIDER=${provider}`, "--env", `FINDWORKS_MODEL=${model}`,
    "--env", `FINDWORKS_TURN_URL=http://host.docker.internal:${port}/turn`,
    "--env", "FINDWORKS_TURN_TOKEN=acceptance-token", "--env", "NO_PROXY=host.docker.internal",
    image,
  ];
  const command = "docker";
  const args = hostContainer
    ? ["exec", "-i", hostContainer, "docker", ...dockerArgs]
    : dockerArgs;
  const child = spawn(command, args, { stdio: ["pipe", "inherit", "inherit"] });
  child.stdin.end(`${JSON.stringify(context)}\n`);
  return new Promise((resolve, reject) => child.on("exit", code => {
    if (code === 0) resolve();
    else reject(new Error(`Runtime exited with ${code}`));
  }));
}
