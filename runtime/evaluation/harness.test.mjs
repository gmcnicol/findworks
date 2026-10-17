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
    outcomes: [{
      resultId,
      coverage: "SUPPORTED",
      category: "TERM",
      evidenceIds: [evidenceId],
      summary: "The team calls the daily prioritisation activity the morning sort.",
    }],
    nextAction: {
      type: "ASK_QUESTION",
      resultId,
      text: "When Maya does the morning sort, what makes one red job come first?",
      responseMode: "FREE_TEXT",
    },
  };
}

test("a valid Runtime Turn is traced with provenance and separate semantic grades", () => {
  const evaluation = evaluateSubmission({
    fixture: fixture(), turnIndex: 0, context: context(), submission: validSubmission(), previousQuestions: [],
  });

  assert.deepEqual(evaluation.contractViolations, []);
  assert.equal(evaluation.semanticQuality.passed, evaluation.semanticQuality.total);
  assert.deepEqual(evaluation.coverageChanges, [{
    resultId,
    from: "EXPLORING",
    to: "SUPPORTED",
    evidenceIds: [evidenceId],
  }]);
  assert.equal(evaluation.trace.sourceAnswer.text, fixture().turns[0].answer.text);
  assert.deepEqual(evaluation.trace.outcomeProvenance[0].evidenceIds, [evidenceId]);
  assert.equal(evaluation.trace.repeatedQuestion, false);
});

test("contract failure identifies turn, rule, source answer, and offending action", () => {
  const submission = validSubmission();
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
});

test("a terminal outcome may close the current thread and ask the next result in one turn", () => {
  const suppliedContext = context();
  suppliedContext.investigation_results.push({
    result_id: otherResultId,
    required: true,
    coverage: "UNADDRESSED",
    current_outcomes: [],
  });
  const submission = validSubmission();
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

test("applying a submission retains immutable provenance and records coverage changes", () => {
  const updated = applySubmission(context(), validSubmission());
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
  const scenario = fixture({
    turns: [fixture().turns[0], {
      runId: "20000000-0000-0000-0000-000000000002",
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
  });
  const comparison = compareWithBaseline(summary, { semanticPassRate: 0.8, deterministicFailures: 0 });
  assert.equal(comparison.regressed, false);
  assert.ok(formatSummary({ metadata: { commit: "abc", model: "openai/gpt", reasoningEffort: "low" }, summary, comparison, runs })
    .includes("Deterministic: 2/2 runs passed"));
});
