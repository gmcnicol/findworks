import assert from "node:assert/strict";
import { mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  applySubmission,
  compareWithBaseline,
  evaluateSubmission,
  formatSummary,
  loadFixtures,
  runScenario,
  summariseRuns,
} from "./harness.mjs";

const resultId = "10000000-0000-0000-0000-000000000001";
const otherResultId = "10000000-0000-0000-0000-000000000002";
const evidenceId = "40000000-0000-0000-0000-000000000001";

function emptyDistribution() {
  return { count: 0, min: null, p25: null, p50: null, p75: null, p95: null, max: null, mean: null };
}

function fixture(overrides = {}) {
  return {
    schemaVersion: 1,
    fixtureVersion: "1.0.0",
    id: "morning-sort",
    title: "Morning sort",
    persona: { name: "Clare", role: "Practice administrator" },
    context: {
      run_id: "20000000-0000-0000-0000-000000000001",
      session_id: "30000000-0000-0000-0000-000000000001",
      mission_version: 1,
      expected_revision: 2,
      mission: { objective: "Understand reconciliation exception triage" },
      shared_mission_entries: [],
      investigation_results: [{
        result_id: resultId,
        knowledge_gap: "Understand reconciliation exception triage",
        priority: "HIGH",
        required: true,
        sufficient_evidence: "Priority, owner, escalation, and completion",
        coverage: "EXPLORING",
        current_outcomes: [],
      }],
      questions_and_evidence: [],
      conversation_state: {},
    },
    turns: [{
      runId: "20000000-0000-0000-0000-000000000001",
      sourceResultId: resultId,
      sourceQuestion: "Walk me through the last mismatch you handled.",
      answer: {
        evidenceId,
        sourceType: "ANSWER",
        text: "Maya calls it the morning sort and checks red jobs first.",
      },
      expected: {
        action: "ASK_QUESTION",
        resultId,
        semantic: {
          vocabularyAnchors: ["Maya", "morning sort", "red jobs"],
          forbiddenTerms: ["architecture", "decision matrix"],
          ownWords: true,
        },
      },
    }],
    ...overrides,
  };
}

function context() {
  const value = structuredClone(fixture().context);
  value.questions_and_evidence.push({
    question_id: "50000000-0000-0000-0000-000000000001",
    sequence: 1,
    question: fixture().turns[0].sourceQuestion,
    active: false,
    evidence_id: evidenceId,
    source_type: "ANSWER",
    exact_text: fixture().turns[0].answer.text,
  });
  value.conversation_state = {
    current_thread_result_id: resultId,
    last_question: fixture().turns[0].sourceQuestion,
    latest_interviewee_wording: fixture().turns[0].answer.text,
  };
  return value;
}

function validSubmission() {
  return {
    runId: fixture().context.run_id,
    expectedRevision: 2,
    outcomes: [],
    nextAction: {
      type: "ASK_QUESTION",
      resultId,
      text: "When Maya does the morning sort, what makes one red job come first?",
      responseMode: "FREE_TEXT",
    },
  };
}

function terminalSubmission() {
  const submission = validSubmission();
  submission.outcomes = [{
    resultId,
    coverage: "SUPPORTED",
    category: "TERM",
    evidenceIds: [evidenceId],
    summary: "The team calls the daily prioritisation activity the morning sort.",
  }];
  return submission;
}

test("a valid Runtime Turn is traced with separate semantic grades", () => {
  const evaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: context(), submission: validSubmission(), previousQuestions: [],
  });

  assert.deepEqual(evaluation.contractViolations, []);
  assert.equal(evaluation.semanticQuality.passed, evaluation.semanticQuality.total);
  assert.deepEqual(evaluation.coverageChanges, []);
  assert.equal(evaluation.trace.sourceAnswer.text, fixture().turns[0].answer.text);
  assert.deepEqual(evaluation.trace.outcomeProvenance, []);
  assert.equal(evaluation.trace.repeatedQuestion, false);
});

test("contract failure identifies turn, rule, source answer, and offending action", () => {
  const submission = terminalSubmission();
  submission.expectedRevision = 99;
  submission.outcomes[0].resultId = otherResultId;
  submission.outcomes[0].evidenceIds = ["unknown-evidence"];
  submission.nextAction.resultId = otherResultId;

  const evaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: context(), submission, previousQuestions: [],
  });

  const stale = evaluation.contractViolations.find(item => item.rule === "expected-revision");
  assert.equal(stale.turn, 1);
  assert.equal(stale.sourceAnswer, fixture().turns[0].answer.text);
  assert.deepEqual(stale.offendingAction, submission.nextAction);
  assert.ok(evaluation.contractViolations.some(item => item.rule === "outcome-result-scope"));
  assert.ok(evaluation.contractViolations.some(item => item.rule === "outcome-evidence-scope"));
  assert.ok(evaluation.contractViolations.some(item => item.rule === "question-result-scope"));
});

test("repeated questions and premature completion are deterministic violations", () => {
  const repeated = validSubmission();
  const first = "When Maya does the morning sort, what makes one red job come first?";
  repeated.nextAction.text = "  When Maya does the morning sort, what makes one red job come first?! ";
  let evaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: context(), submission: repeated, previousQuestions: [first],
  });
  assert.equal(evaluation.trace.repeatedQuestion, true);
  assert.ok(evaluation.contractViolations.some(item => item.rule === "repeated-question"));

  repeated.nextAction.text = "What makes a red job get priority during Maya's morning sort?";
  evaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: context(), submission: repeated, previousQuestions: [first],
  });
  assert.equal(evaluation.trace.repeatedQuestion, true);
  assert.ok(evaluation.contractViolations.some(item => item.rule === "semantically-repeated-question"));

  const completion = validSubmission();
  completion.outcomes = [];
  completion.nextAction = { type: "PROPOSE_COMPLETION" };
  evaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: context(), submission: completion, previousQuestions: [],
  });
  assert.ok(evaluation.contractViolations.some(item => item.rule === "premature-completion"));
});

test("fixture-defined focus and pressure checks make bounded follow-ups reviewable", () => {
  const scenario = fixture();
  scenario.turns[0].expected.semantic.focusAnchors = ["what makes", "example"];
  scenario.turns[0].expected.semantic.pressureTerms = ["guess", "email", "invite"];
  const focused = evaluateSubmission({
    fixture: scenario, turnIndex: 0, context: context(), submission: validSubmission(), previousQuestions: [],
  });
  assert.equal(focused.semanticQuality.checks.find(check => check.rule === "useful-follow-up-focus").passed, true);
  assert.equal(focused.semanticQuality.checks.find(check => check.rule === "no-pressure-or-recruitment").passed, true);

  const pressured = validSubmission();
  pressured.nextAction.text = "Could you guess Maya's email so I can invite her?";
  const evaluation = evaluateSubmission({
    fixture: scenario, turnIndex: 0, context: context(), submission: pressured, previousQuestions: [],
  });
  assert.equal(evaluation.semanticQuality.checks.find(check => check.rule === "useful-follow-up-focus").passed, false);
  const pressure = evaluation.semanticQuality.checks.find(check => check.rule === "no-pressure-or-recruitment");
  assert.equal(pressure.passed, false);
  assert.match(pressure.detail, /guess, email, invite/);
});

test("sentence-opening transition words are not mistaken for unsupported proper names", () => {
  for (const text of [
    "After Maya finishes the morning sort, what happens to the red job?",
    "Once Maya finishes the morning sort, what happens to the red job?",
  ]) {
    const submission = validSubmission();
    submission.nextAction.text = text;
    const evaluation = evaluateSubmission({
      fixture: fixture(), turnIndex: 0, context: context(), submission, previousQuestions: [],
    });
    assert.ok(!evaluation.contractViolations.some(item => item.rule === "unsupported-specific"));
  }

  const invented = validSubmission();
  invented.nextAction.text = "Priya finishes the morning sort; what happens to the red job?";
  const evaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: context(), submission: invented, previousQuestions: [],
  });
  assert.ok(evaluation.contractViolations.some(item => item.rule === "unsupported-specific"));
});

test("long-scenario assertions reject planning leaks, fabricated specifics, and ungrounded choices", () => {
  const scenario = fixture({
    assertions: {
      forbiddenQuestionTerms: ["sufficiency criterion"],
      forbiddenClaims: ["automatic cancellation"],
    },
  });
  const submission = validSubmission();
  submission.nextAction = {
    type: "ASK_QUESTION",
    resultId,
    text: "Does Priya's sufficiency criterion mean automatic cancellation above £500?",
    responseMode: "CHOICE",
    options: [
      { id: "auto", label: "Automatically cancel it" },
      { id: "manual", label: "Send it to the robot queue" },
    ],
  };

  const evaluation = evaluateSubmission({
    fixture: scenario, turnIndex: 0, context: context(), submission, previousQuestions: [],
  });

  assert.ok(evaluation.contractViolations.some(item => item.rule === "mission-language-leak"));
  assert.ok(evaluation.contractViolations.some(item => item.rule === "fabricated-fact"));
  assert.ok(evaluation.contractViolations.some(item => item.rule === "unsupported-specific"));
  assert.ok(evaluation.contractViolations.some(item => item.rule === "ungrounded-choice"));

  const grounded = validSubmission();
  grounded.nextAction.text = "Besides Maya and the red jobs, what affects the morning sort?";
  const groundedEvaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: context(), submission: grounded, previousQuestions: [],
  });
  assert.ok(!groundedEvaluation.contractViolations.some(item => item.rule === "unsupported-specific"));
});

test("a terminal outcome may close the current thread and ask the next result in one explained transition", () => {
  const suppliedContext = context();
  suppliedContext.investigation_results.push({
    result_id: otherResultId,
    required: true,
    coverage: "UNADDRESSED",
    current_outcomes: [],
  });
  const submission = terminalSubmission();
  submission.nextAction = {
    type: "ASK_QUESTION",
    resultId: otherResultId,
    text: "Who decides which return is checked next?",
    responseMode: "FREE_TEXT",
  };
  const scenario = fixture();
  scenario.turns[0].expected.resultId = otherResultId;
  const evaluation = evaluateSubmission({
    fixture: scenario, turnIndex: 0, context: suppliedContext, submission, previousQuestions: [],
  });
  assert.ok(!evaluation.contractViolations.some(item => item.rule === "current-thread"));
  assert.deepEqual(evaluation.trace.threadTransition, {
    fromResultId: resultId,
    toResultId: otherResultId,
    reason: "SUPPORTED",
    evidenceIds: [evidenceId],
  });
});

test("a Question cannot continue a thread made terminal in the same turn", () => {
  const submission = terminalSubmission();
  const evaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: context(), submission, previousQuestions: [],
  });
  assert.ok(evaluation.contractViolations.some(item => item.rule === "terminal-thread-continued"));
});

test("finished threads stay finished and required results precede optional ones", () => {
  const suppliedContext = context();
  suppliedContext.investigation_results[0].coverage = "SUPPORTED";
  suppliedContext.investigation_results.push({
    result_id: otherResultId,
    required: true,
    coverage: "UNADDRESSED",
    current_outcomes: [],
  });
  suppliedContext.conversation_state.current_thread_result_id = otherResultId;
  const reopened = validSubmission();
  reopened.outcomes = [];
  reopened.nextAction.resultId = resultId;
  let evaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: suppliedContext, submission: reopened, previousQuestions: [],
  });
  assert.ok(evaluation.contractViolations.some(item => item.rule === "finished-thread-reopened"));

  const optionalResultId = "10000000-0000-0000-0000-000000000003";
  suppliedContext.investigation_results.push({
    result_id: optionalResultId,
    required: false,
    coverage: "UNADDRESSED",
    current_outcomes: [],
  });
  const skippedRequired = validSubmission();
  skippedRequired.outcomes = [];
  skippedRequired.nextAction.resultId = optionalResultId;
  evaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: suppliedContext, submission: skippedRequired, previousQuestions: [],
  });
  assert.ok(evaluation.contractViolations.some(item => item.rule === "required-result-priority"));
});

test("an optional thread already in progress stays coherent before required work resumes", () => {
  const optionalResultId = "10000000-0000-0000-0000-000000000003";
  const suppliedContext = context();
  suppliedContext.investigation_results.push({
    result_id: optionalResultId,
    required: false,
    coverage: "EXPLORING",
    current_outcomes: [],
  }, {
    result_id: otherResultId,
    required: true,
    coverage: "UNADDRESSED",
    current_outcomes: [],
  });
  suppliedContext.conversation_state.current_thread_result_id = optionalResultId;
  const submission = validSubmission();
  submission.nextAction.resultId = optionalResultId;
  const evaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: suppliedContext, submission, previousQuestions: [],
  });
  assert.ok(!evaluation.contractViolations.some(item => item.rule === "required-result-priority"));
});

test("explicit open-state expectations distinguish a mention from sufficient Evidence", () => {
  const scenario = fixture();
  scenario.turns[0].expected.openResultIds = [resultId];
  const submission = terminalSubmission();
  const evaluation = evaluateSubmission({
    fixture: scenario, turnIndex: 0, context: context(), submission, previousQuestions: [],
  });
  assert.ok(evaluation.semanticQuality.checks.some(check => check.rule === `expected-open:${resultId}` && !check.passed));
});

test("outcome summaries can require every evidence-critical qualifier", () => {
  const scenario = fixture();
  scenario.turns[0].expected.outcomes = [{
    resultId,
    coverage: "SUPPORTED",
    evidenceIds: [evidenceId],
    summaryIncludesAll: ["morning sort", "unconfirmed"],
  }];

  const submission = terminalSubmission();
  submission.nextAction = { type: "PROPOSE_COMPLETION" };
  scenario.turns[0].expected.action = "PROPOSE_COMPLETION";
  const evaluation = evaluateSubmission({
    fixture: scenario, turnIndex: 0, context: context(), submission, previousQuestions: [],
  });

  const summary = evaluation.semanticQuality.checks.find(check => check.rule === "expected-summary-all:SUPPORTED");
  assert.equal(summary.passed, false);
  assert.match(summary.detail, /unconfirmed/);
});

test("outcome summaries accept reviewed wording alternatives without dropping required concepts", () => {
  const scenario = fixture();
  scenario.turns[0].expected.outcomes = [{
    resultId,
    coverage: "SUPPORTED",
    evidenceIds: [evidenceId],
    summaryAnchorGroups: [["morning sort"], ["unconfirmed", "not confirmed"]],
  }];

  const submission = terminalSubmission();
  submission.nextAction = { type: "PROPOSE_COMPLETION" };
  scenario.turns[0].expected.action = "PROPOSE_COMPLETION";
  const evaluation = evaluateSubmission({
    fixture: scenario, turnIndex: 0, context: context(), submission, previousQuestions: [],
  });

  const summary = evaluation.semanticQuality.checks.find(check => check.rule === "expected-summary-groups:SUPPORTED");
  assert.equal(summary.passed, false);
  assert.match(summary.detail, /unconfirmed \| not confirmed/);
});

test("unsupported outcome kinds fail semantic grading without weakening contract checks", () => {
  const scenario = fixture();
  scenario.turns[0].expected.outcomes = [{
    resultId,
    coverage: "ASSUMPTION",
    category: "ASSUMPTION",
    evidenceIds: [evidenceId],
  }];
  scenario.turns[0].expected.forbiddenOutcomes = [{ resultId, coverage: "SUPPORTED" }];
  const submission = terminalSubmission();
  submission.outcomes.push({
    resultId,
    coverage: "ASSUMPTION",
    category: "ASSUMPTION",
    evidenceIds: [evidenceId],
    summary: "The supposed rule is unconfirmed.",
  });
  submission.nextAction = { type: "PROPOSE_COMPLETION" };
  scenario.turns[0].expected.action = "PROPOSE_COMPLETION";

  const evaluation = evaluateSubmission({
    fixture: scenario, turnIndex: 0, context: context(), submission, previousQuestions: [],
  });

  assert.deepEqual(evaluation.contractViolations, []);
  const distinction = evaluation.semanticQuality.checks.find(check => check.rule === "forbidden-outcome:SUPPORTED");
  assert.equal(distinction.passed, false);
  assert.match(distinction.detail, /Unexpected SUPPORTED outcome/);
});

test("expected final outcomes are graded from cumulative result state, not only the turn delta", () => {
  const suppliedContext = context();
  suppliedContext.investigation_results[0].coverage = "SUPPORTED";
  suppliedContext.investigation_results[0].current_outcomes = [{
    coverage: "SUPPORTED",
    category: "TERM",
    summary: "The team calls it the morning sort.",
    evidence_ids: [evidenceId],
  }];
  const scenario = fixture();
  scenario.turns[0].expected = {
    action: "PROPOSE_COMPLETION",
    outcomes: [{ resultId, coverage: "SUPPORTED", category: "TERM", evidenceIds: [evidenceId], summaryIncludes: ["morning sort"] }],
  };
  const evaluation = evaluateSubmission({
    fixture: scenario,
    turnIndex: 0,
    context: suppliedContext,
    submission: {
      runId: scenario.context.run_id,
      expectedRevision: 2,
      outcomes: [],
      nextAction: { type: "PROPOSE_COMPLETION" },
    },
  });
  assert.equal(evaluation.semanticQuality.passed, evaluation.semanticQuality.total);
});

test("new contradictory Evidence moves a supported result to Conflict without erasing prior provenance", () => {
  const suppliedContext = context();
  suppliedContext.investigation_results[0].coverage = "SUPPORTED";
  suppliedContext.investigation_results[0].current_outcomes = [{
    coverage: "SUPPORTED",
    category: "RULE",
    summary: "The handbook states four hours.",
    evidence_ids: [evidenceId],
  }];
  const contradictionId = "40000000-0000-0000-0000-000000000002";
  suppliedContext.questions_and_evidence.push({
    question_id: "50000000-0000-0000-0000-000000000002",
    evidence_id: contradictionId,
    exact_text: "The current rota states two hours.",
  });
  const submission = {
    runId: suppliedContext.run_id,
    expectedRevision: suppliedContext.expected_revision,
    outcomes: [{
      resultId,
      coverage: "CONFLICT",
      evidenceIds: [evidenceId, contradictionId],
      summary: "The handbook states four hours while the current rota states two hours.",
    }],
    nextAction: { type: "PROPOSE_COMPLETION" },
  };

  const evaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: suppliedContext, submission, previousQuestions: [],
  });
  const updated = applySubmission(suppliedContext, submission);

  assert.deepEqual(evaluation.coverageChanges, [{
    resultId,
    from: "SUPPORTED",
    to: "CONFLICT",
    evidenceIds: [evidenceId, contradictionId],
  }]);
  assert.equal(updated.investigation_results[0].coverage, "CONFLICT");
  assert.equal(updated.investigation_results[0].current_outcomes[0].coverage, "SUPPORTED");
  assert.equal(updated.investigation_results[0].current_outcomes[1].coverage, "CONFLICT");
});

test("applying a submission retains immutable provenance and records coverage changes", () => {
  const updated = applySubmission(context(), terminalSubmission());
  assert.equal(updated.investigation_results[0].coverage, "SUPPORTED");
  assert.deepEqual(updated.investigation_results[0].current_outcomes[0].evidence_ids, [evidenceId]);
  assert.equal(updated.questions_and_evidence[0].exact_text, fixture().turns[0].answer.text);
});

test("fixture discovery is versioned and does not require harness edits", async () => {
  const directory = await mkdtemp(join(tmpdir(), "findworks-fixtures-"));
  await writeFile(join(directory, "b.json"), JSON.stringify(fixture({ id: "b" })));
  await writeFile(join(directory, "a.json"), JSON.stringify(fixture({ id: "a" })));

  const fixtures = await loadFixtures(directory);
  assert.deepEqual(fixtures.map(value => value.id), ["a", "b"]);
});

test("the ambiguity portfolio covers bounded clarification and every honest unresolved outcome", async () => {
  const fixtures = await loadFixtures(new URL("./fixtures/v1", import.meta.url).pathname);
  const byId = new Map(fixtures.map(value => [value.id, value]));
  const required = [
    "vague-partial-answer",
    "uncertain-recollection",
    "assumption-outcome",
    "contradiction-within-interview",
    "conflict-outcome",
    "disputed-terminology",
    "ownership-gap",
    "incomplete-owner-referral",
    "unknown-outcome",
    "refusal-to-answer",
  ];
  assert.deepEqual(required.filter(id => !byId.has(id)), []);
  for (const id of required) assert.equal(byId.get(id).minimumSemanticPassRate, 1);

  assert.equal(byId.get("vague-partial-answer").turns[0].expected.action, "ASK_QUESTION");
  assert.equal(byId.get("disputed-terminology").turns[0].expected.action, "ASK_QUESTION");
  assert.deepEqual(
    byId.get("contradiction-within-interview").turns.at(-1).expected.outcomes.map(value => value.coverage),
    ["CONFLICT"],
  );
  assert.deepEqual(
    byId.get("incomplete-owner-referral").turns[0].expected.outcomes.map(value => value.coverage),
    ["OWNERSHIP_GAP"],
  );
  assert.deepEqual(
    byId.get("refusal-to-answer").turns[0].expected.outcomes.map(value => value.coverage),
    ["UNKNOWN"],
  );
});

test("the refund completion fixture resolves the exact five-hundred-pound boundary", async () => {
  const fixtures = await loadFixtures(new URL("./fixtures/v1", import.meta.url).pathname);
  const refund = fixtures.find(value => value.id === "refund-completion");
  assert.match(refund.turns[0].answer.text, /five hundred pounds or less/i);
  assert.match(refund.turns[1].answer.text, /more than five hundred pounds/i);
});

test("the shared suite covers thread transitions, mentions, required priority, and Evidence reuse", async () => {
  const fixtures = await loadFixtures(new URL("./fixtures/v1", import.meta.url).pathname);
  const byId = new Map(fixtures.map(value => [value.id, value]));
  assert.ok(byId.has("mention-is-not-sufficient"));
  assert.ok(byId.has("parked-thread-transition"));
  assert.ok(byId.has("required-before-optional"));
  assert.ok(byId.has("shared-evidence-multiple-results"));
  for (const id of [
    "mention-is-not-sufficient", "parked-thread-transition", "required-before-optional", "shared-evidence-multiple-results",
  ]) {
    assert.equal(byId.get(id).minimumSemanticPassRate, 1);
    assert.ok(byId.get(id).turns.every(turn => typeof turn.sourceResultId === "string"));
  }
  assert.ok(byId.get("mention-is-not-sufficient").turns[0].expected.openResultIds.length > 0);
  assert.equal(byId.get("parked-thread-transition").turns[1].expected.resultId,
    byId.get("parked-thread-transition").context.investigation_results[1].result_id);
  const sharedEvidence = byId.get("shared-evidence-multiple-results").turns[0].answer.evidenceId;
  assert.ok(byId.get("shared-evidence-multiple-results").turns[0].expected.outcomes
    .every(outcome => outcome.evidenceIds.includes(sharedEvidence)));
});

test("versioned long fixtures cover 20 and 40 turns plus continuity stressors", async () => {
  const fixtures = await loadFixtures(new URL("./fixtures/v1", import.meta.url).pathname);
  const twenty = fixtures.find(value => value.id === "claims-routing-20-turn-continuity");
  const forty = fixtures.find(value => value.id === "permit-handover-40-turn-compaction");

  assert.ok(twenty.turns.length >= 20);
  assert.ok(forty.turns.length >= 40);
  for (const scenario of [twenty, forty]) {
    assert.ok(scenario.turns.some(turn => turn.answer.text.length >= 300));
    for (const stressor of ["vocabulary-change", "interruption", "cross-thread", "prior-statement-revision"]) {
      assert.ok(scenario.turns.some(turn => turn.stressor === stressor), `${scenario.id} lacks ${stressor}`);
    }
  }
  assert.ok(forty.contextPolicy.maxQuestionEvidenceRecords < forty.turns.length);
  assert.equal(twenty.assertions.minimumTurns, 20);
  assert.equal(forty.assertions.minimumTurns, 40);
});

test("a scenario drives every answer through the Runtime Turn seam and records available usage", async () => {
  const submissions = [];
  const observedContexts = [];
  const scenario = fixture({
    turns: [fixture().turns[0], {
      runId: "20000000-0000-0000-0000-000000000002",
      sourceResultId: resultId,
      sourceQuestion: "Fallback source question",
      answer: {
        evidenceId: "40000000-0000-0000-0000-000000000002",
        sourceType: "DONT_KNOW",
        text: "I do not know; nobody here owns that decision.",
      },
      expected: {
        action: "PROPOSE_COMPLETION",
        outcomes: [{ resultId, coverage: "UNKNOWN", evidenceIds: ["40000000-0000-0000-0000-000000000002"] }],
      },
    }],
  });
  const run = await runScenario({
    fixture: scenario,
    repeat: 2,
    executeRuntime: async suppliedContext => {
      observedContexts.push(suppliedContext);
      const turn = submissions.length;
      const submission = turn === 0 ? validSubmission() : {
        runId: suppliedContext.run_id,
        expectedRevision: suppliedContext.expected_revision,
        outcomes: [{
          resultId,
          coverage: "UNKNOWN",
          evidenceIds: ["40000000-0000-0000-0000-000000000002"],
          summary: "The interviewee does not know who owns the decision.",
        }],
        nextAction: { type: "PROPOSE_COMPLETION" },
      };
      submissions.push(submission);
      return { submission, durationMs: 25, usage: turn === 0 ? { inputTokens: 100, outputTokens: 20, costUsd: 0.001 } : null };
    },
  });

  assert.equal(submissions.length, 2);
  assert.notEqual(submissions[0].runId, submissions[1].runId);
  assert.equal(run.turns[1].trace.sourceQuestion, validSubmission().nextAction.text);
  assert.equal(observedContexts[1].conversation_state.current_thread_result_id, validSubmission().nextAction.resultId);
  assert.equal(run.turns[1].trace.sourceAnswer.sourceType, "DONT_KNOW");
  assert.equal(run.turns[1].contractViolations.length, 0);
  assert.deepEqual(run.usage, {
    inputTokens: 100,
    outputTokens: 20,
    cacheWriteTokens: null,
    costUsd: 0.001,
    availableTurns: 1,
  });
  assert.equal(run.durationMs, 50);
  assert.equal(run.semanticGatePassed, true);
});

test("a scenario records rejected reviewer proposals plus role-specific latency, cost, and cadence", async () => {
  const run = await runScenario({
    fixture: fixture(),
    repeat: 1,
    executeRuntime: async () => ({
      submission: validSubmission(),
      durationMs: 80,
      interviewerDurationMs: 50,
      reviewerDurationMs: 30,
      usage: { inputTokens: 170, outputTokens: 30, cacheWriteTokens: 0, costUsd: 0.005 },
      interviewerUsage: { inputTokens: 100, outputTokens: 20, costUsd: 0.003 },
      reviewerUsage: { inputTokens: 70, outputTokens: 10, costUsd: 0.002 },
      review: {
        accepted: false,
        trigger: "INITIAL_EVIDENCE",
        requestId: "review-1",
        rejection: "reviewer_revision_stale",
      },
    }),
  });

  assert.ok(run.turns[0].contractViolations.some(value => value.rule === "reviewer-proposal-rejected"));
  assert.deepEqual(run.metrics.reviewer, {
    invocations: 1,
    accepted: 0,
    rejected: 1,
    cadence: 1,
    triggers: { INITIAL_EVIDENCE: 1 },
    latencyMs: { p50: 30, p95: 30 },
  });
  assert.equal(run.turns[0].trace.review.requestId, "review-1");
  assert.equal(run.turns[0].interviewerDurationMs, 50);
  assert.equal(run.turns[0].reviewerDurationMs, 30);
  assert.equal(run.turns[0].interviewerUsage.costUsd, 0.003);
  assert.equal(run.turns[0].reviewerUsage.costUsd, 0.002);
});

test("a scenario uses the source Question result rather than leaking the expected next result", async () => {
  const scenario = fixture();
  scenario.context.investigation_results.push({
    result_id: otherResultId,
    required: true,
    coverage: "UNADDRESSED",
    current_outcomes: [],
  });
  scenario.turns[0].sourceResultId = resultId;
  scenario.turns[0].expected.resultId = otherResultId;
  let observedContext;
  await runScenario({
    fixture: scenario,
    repeat: 1,
    executeRuntime: async suppliedContext => {
      observedContext = suppliedContext;
      const submission = terminalSubmission();
      submission.nextAction.resultId = otherResultId;
      return { submission, durationMs: 1, usage: null };
    },
  });
  assert.equal(observedContext.conversation_state.current_thread_result_id, resultId);
});

test("a premature completion stops the scenario instead of fabricating another Question", async () => {
  const secondTurn = structuredClone(fixture().turns[0]);
  secondTurn.runId = "20000000-0000-0000-0000-000000000099";
  secondTurn.answer.evidenceId = "40000000-0000-0000-0000-000000000099";
  const scenario = fixture({ turns: [fixture().turns[0], secondTurn] });
  let executions = 0;
  const run = await runScenario({
    fixture: scenario,
    repeat: 1,
    executeRuntime: async suppliedContext => {
      executions += 1;
      return {
        submission: {
          runId: suppliedContext.run_id,
          expectedRevision: suppliedContext.expected_revision,
          outcomes: [],
          nextAction: { type: "PROPOSE_COMPLETION" },
        },
        durationMs: 1,
        usage: null,
      };
    },
  });
  assert.equal(executions, 1);
  assert.equal(run.turns.length, 1);
  assert.ok(run.turns[0].contractViolations.some(item => item.rule === "premature-completion"));
});

test("a fixture can preserve hard semantic acceptance without reclassifying it as contract", async () => {
  const scenario = fixture({ minimumSemanticPassRate: 1 });
  const submission = validSubmission();
  submission.nextAction.text = "What happens next?";
  const run = await runScenario({
    fixture: scenario,
    repeat: 1,
    executeRuntime: async () => ({ submission, durationMs: 1, usage: null }),
  });
  assert.equal(run.turns[0].contractViolations.length, 0);
  assert.equal(run.semanticGatePassed, false);
});

test("long runs measure continuity, compaction, coverage, evidence yield, phases, and latency percentiles", async () => {
  const scenario = fixture({
    fixtureVersion: "2.0.0",
    assertions: {
      minimumTurns: 4,
      criticalEvidenceIds: [
        "40000000-0000-0000-0000-000000000001",
        "40000000-0000-0000-0000-000000000002",
      ],
    },
    contextPolicy: { maxQuestionEvidenceRecords: 2 },
    turns: Array.from({ length: 4 }, (_, index) => ({
      runId: `20000000-0000-0000-0000-00000000000${index + 1}`,
      sourceQuestion: `Question ${index + 1}?`,
      answer: {
        resultId,
        evidenceId: `40000000-0000-0000-0000-00000000000${index + 1}`,
        sourceType: "ANSWER",
        text: `The morning sort fact number ${index + 1} is grounded in this synthetic answer.`,
      },
      expected: {
        action: index === 3 ? "PROPOSE_COMPLETION" : "ASK_QUESTION",
        resultId: index === 3 ? undefined : resultId,
      },
    })),
  });
  let turn = 0;
  const run = await runScenario({
    fixture: scenario,
    repeat: 1,
    executeRuntime: async suppliedContext => {
      const index = turn++;
      const currentEvidenceId = scenario.turns[index].answer.evidenceId;
      return {
        durationMs: [10, 20, 30, 100][index],
        usage: { inputTokens: 100 + index, outputTokens: 10, costUsd: 0.001 },
        submission: {
          runId: suppliedContext.run_id,
          expectedRevision: suppliedContext.expected_revision,
          outcomes: [{
            resultId,
            coverage: "SUPPORTED",
            category: "FACT",
            evidenceIds: [currentEvidenceId],
            summary: `Grounded fact ${index + 1}`,
          }],
          nextAction: index === 3
            ? { type: "PROPOSE_COMPLETION" }
            : { type: "ASK_QUESTION", resultId, text: `What is new fact ${index + 2}?`, responseMode: "FREE_TEXT" },
        },
      };
    },
  });

  assert.equal(run.turns.length, 4);
  assert.deepEqual(run.metrics.requiredCoverage, { total: 1, terminal: 1, completionRate: 1 });
  assert.equal(run.metrics.usefulEvidence.questions, 4);
  assert.equal(run.metrics.usefulEvidence.answersLinkedToOutcomes, 4);
  assert.equal(run.metrics.usefulEvidence.perQuestion, 1);
  assert.deepEqual(run.metrics.latencyMs, { p50: 25, p95: 89.5 });
  assert.deepEqual(run.metrics.compaction, {
    enabled: true,
    recordsDropped: 2,
    criticalEvidenceRetained: true,
    missingCriticalEvidenceIds: [],
  });
  assert.equal(run.metrics.continuity.allInvestigatedItemsTraceable, true);
  assert.equal(run.metrics.qualityByPhase.length, 3);
  assert.equal(run.continuityGatePassed, true);
  assert.ok(run.turns.some(value => value.trace.compactedEvidenceIds.length > 0));
});

test("an unaddressed optional result does not fail continuity after required completion", async () => {
  const optionalResultId = "10000000-0000-0000-0000-000000000003";
  const scenario = fixture();
  scenario.context.investigation_results.push({
    result_id: optionalResultId,
    required: false,
    coverage: "UNADDRESSED",
    current_outcomes: [],
  });
  scenario.turns[0].expected = {
    action: "PROPOSE_COMPLETION",
    outcomes: [{ resultId, coverage: "SUPPORTED", evidenceIds: [evidenceId] }],
  };
  const run = await runScenario({
    fixture: scenario,
    repeat: 1,
    executeRuntime: async suppliedContext => {
      const submission = terminalSubmission();
      submission.runId = suppliedContext.run_id;
      submission.nextAction = { type: "PROPOSE_COMPLETION" };
      return { submission, durationMs: 1, usage: null };
    },
  });
  assert.equal(run.metrics.continuity.allInvestigatedItemsTraceable, true);
  assert.equal(run.continuityGatePassed, true);
});

test("runtime failures remain reviewable and stop only the affected scenario", async () => {
  const run = await runScenario({
    fixture: fixture(),
    repeat: 1,
    executeRuntime: async () => { throw new Error("provider unavailable"); },
  });
  assert.equal(run.turns.length, 1);
  const failure = run.turns[0].contractViolations.find(item => item.rule === "runtime-execution");
  assert.equal(failure.turn, 1);
  assert.equal(failure.sourceAnswer, fixture().turns[0].answer.text);
  assert.equal(failure.offendingAction, null);
  assert.equal(run.turns[0].executionError, "provider unavailable");
});

test("summary distributions expose benchmark quality, safety, turns, latency, and cost", () => {
  const runs = [{
    scenarioId: "conflict",
    repeat: 1,
    durationMs: 30,
    usage: { costUsd: 0.03 },
    metrics: {
      requiredCoverage: { completionRate: 1 },
      requiredCoverageProgress: [
        { turn: 1, terminal: 0, total: 1, completionRate: 0 },
        { turn: 2, terminal: 1, total: 1, completionRate: 1 },
      ],
      usefulEvidence: { perQuestion: 0.5 },
    },
    turns: [{
      durationMs: 10,
      contractViolations: [{ rule: "semantically-repeated-question" }],
      semanticQuality: { passed: 2, total: 3, checks: [{ rule: "expected-outcome:CONFLICT", passed: false }] },
      trace: { repeatedQuestion: true, action: { type: "ASK_QUESTION" } },
    }, {
      durationMs: 20,
      contractViolations: [{ rule: "premature-completion" }],
      semanticQuality: { passed: 1, total: 1, checks: [] },
      trace: { repeatedQuestion: false, action: { type: "PROPOSE_COMPLETION" } },
    }],
  }];

  const summary = summariseRuns(runs);

  assert.deepEqual(summary.distributions.semanticPassRate, {
    count: 1, min: 0.75, p25: 0.75, p50: 0.75, p75: 0.75, p95: 0.75, max: 0.75, mean: 0.75,
  });
  assert.equal(summary.distributions.turnsToSufficientEvidence.p50, 2);
  assert.equal(summary.distributions.latencyMs.p95, 19.5);
  assert.equal(summary.distributions.costUsdPerRun.p50, 0.03);
  assert.deepEqual(summary.failureCounts, {
    contradictionsMissed: 1,
    repeatedQuestions: 1,
    earlyCompletions: 1,
    completionErrors: 1,
  });
  assert.deepEqual(summary.reviewer, {
    invocations: 0,
    accepted: 0,
    rejected: 0,
    eligibleTurns: 2,
    cadence: 0,
    triggers: {},
    latencyMs: emptyDistribution(),
  });
});

test("repeat summaries compare deterministic and graded results with a baseline", () => {
  const runs = [
    { scenarioId: "morning-sort", repeat: 1, durationMs: 100, turns: [{ contractViolations: [], semanticQuality: { passed: 3, total: 3 } }] },
    { scenarioId: "morning-sort", repeat: 2, durationMs: 120, turns: [{ contractViolations: [], semanticQuality: { passed: 2, total: 3 } }] },
  ];
  const summary = summariseRuns(runs);
  assert.deepEqual(summary, {
    scenarios: 1,
    runs: 2,
    turns: 2,
    deterministicPasses: 2,
    deterministicFailures: 0,
    semanticGateFailures: 0,
    continuityGateFailures: 0,
    semanticPassed: 5,
    semanticTotal: 6,
    semanticPassRate: 5 / 6,
    requiredCoverageCompletionRate: null,
    usefulEvidencePerQuestion: null,
    latencyMs: { p50: null, p95: null },
    durationMs: 220,
    distributions: {
      semanticPassRate: {
        count: 2, min: 2 / 3, p25: 0.75, p50: 0.833, p75: 0.917, p95: 0.983, max: 1, mean: (1 + 2 / 3) / 2,
      },
      requiredCoverageCompletionRate: emptyDistribution(),
      usefulEvidencePerQuestion: emptyDistribution(),
      turnsToSufficientEvidence: emptyDistribution(),
      latencyMs: emptyDistribution(),
      costUsdPerRun: emptyDistribution(),
      costUsdPerQuestion: emptyDistribution(),
    },
    runsWithoutSufficientEvidence: 2,
    failureCounts: { contradictionsMissed: 0, repeatedQuestions: 0, earlyCompletions: 0, completionErrors: 0 },
    reviewer: {
      invocations: 0,
      accepted: 0,
      rejected: 0,
      eligibleTurns: 2,
      cadence: 0,
      triggers: {},
      latencyMs: emptyDistribution(),
    },
  });
  const comparison = compareWithBaseline(summary, { semanticPassRate: 0.8, deterministicFailures: 0 });
  assert.equal(comparison.regressed, false);
  assert.ok(formatSummary({ metadata: { commit: "abc", model: "openai/gpt", reasoningEffort: "low" }, summary, comparison, runs })
    .includes("Deterministic: 2/2 runs passed"));
});
