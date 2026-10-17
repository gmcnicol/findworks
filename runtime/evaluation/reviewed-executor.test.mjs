import assert from "node:assert/strict";
import test from "node:test";
import { ReviewerProposalGate } from "./review-contract.mjs";
import { createReviewedExecutor, compareReviewStrategies } from "./reviewed-executor.mjs";

const resultId = "10000000-0000-0000-0000-000000000001";
const evidenceId = "40000000-0000-0000-0000-000000000001";
const modelIdentity = {
  provider: "openai",
  requestedModel: "gpt-5.4-mini",
  responseModel: "gpt-5.4-mini-2026-03-17",
  reasoningEffort: "low",
};

function context() {
  return {
    run_id: "20000000-0000-0000-0000-000000000001",
    session_id: "30000000-0000-0000-0000-000000000001",
    mission_version: 1,
    expected_revision: 2,
    mission: { objective: "Understand triage" },
    shared_mission_entries: [],
    investigation_results: [{
      result_id: resultId,
      required: true,
      coverage: "EXPLORING",
      current_outcomes: [],
    }],
    questions_and_evidence: [{
      question_id: "q1",
      evidence_id: evidenceId,
      source_type: "ANSWER",
      exact_text: "Maya checks red jobs first.",
    }],
    conversation_state: { current_thread_result_id: resultId },
  };
}

function reviewProposal(request) {
  return {
    schemaVersion: 1,
    requestId: request.requestId,
    proposalId: `proposal-${request.expectedRevision}`,
    sessionId: request.sessionId,
    missionVersion: request.missionVersion,
    expectedRevision: request.expectedRevision,
    evidenceRevision: request.evidenceRevision,
    outcomeProposals: [],
    nextThreadProposal: { resultId, reason: "Stay on the required thread.", evidenceIds: [evidenceId] },
    completionAssessment: {
      status: "NOT_READY",
      openRequiredResultIds: [resultId],
      reason: "Required work remains open.",
      evidenceIds: [evidenceId],
    },
  };
}

test("a scheduled review is validated before advisory guidance reaches the interviewer", async () => {
  let reviewerRequest;
  let interviewerContext;
  const execute = createReviewedExecutor({
    gate: new ReviewerProposalGate(),
    executeReviewer: async request => {
      reviewerRequest = request;
      return {
        proposal: reviewProposal(request),
        durationMs: 30,
        usage: { inputTokens: 70, outputTokens: 10, costUsd: 0.002, modelIdentity },
      };
    },
    executeInterviewer: async supplied => {
      interviewerContext = supplied;
      return {
        submission: {
          runId: supplied.run_id,
          expectedRevision: supplied.expected_revision,
          outcomes: [],
          nextAction: { type: "ASK_QUESTION", resultId, text: "What makes a red job come first?", responseMode: "FREE_TEXT" },
        },
        event: "question_ready",
        durationMs: 50,
        usage: { inputTokens: 100, outputTokens: 20, costUsd: 0.003, modelIdentity },
      };
    },
  });

  const result = await execute(context());

  assert.equal(reviewerRequest.trigger, "INITIAL_EVIDENCE");
  assert.equal(interviewerContext.reviewer_guidance.proposal_id, "proposal-2");
  assert.equal(result.durationMs, 80);
  assert.deepEqual(result.usage, {
    inputTokens: 170,
    outputTokens: 30,
    cacheWriteTokens: null,
    costUsd: 0.005,
    modelIdentity,
  });
  assert.equal(result.review.accepted, true);
  assert.equal(result.review.executionSucceeded, true);
  assert.equal(result.review.trigger, "INITIAL_EVIDENCE");
  assert.equal(result.interviewerDurationMs, 50);
  assert.equal(result.reviewerDurationMs, 30);
});

test("an invalid review never reaches interviewer context and remains visible in the trace", async () => {
  let interviewerContext;
  const execute = createReviewedExecutor({
    gate: new ReviewerProposalGate(),
    executeReviewer: async request => {
      const invalid = reviewProposal(request);
      invalid.expectedRevision += 1;
      return { proposal: invalid, durationMs: 20, usage: null };
    },
    executeInterviewer: async supplied => {
      interviewerContext = supplied;
      return { submission: null, durationMs: 40, usage: null };
    },
  });

  const result = await execute(context());

  assert.equal(interviewerContext.reviewer_guidance, undefined);
  assert.equal(result.review.accepted, false);
  assert.equal(result.review.executionSucceeded, true);
  assert.match(result.review.rejection, /reviewer_revision_stale/);
  assert.equal(result.durationMs, 60);
});

test("comparison makes Evidence yield, conflicts, completion, cost, latency, and cadence explicit", () => {
  const baseline = {
    usefulEvidencePerQuestion: 0.5,
    latencyMs: { p95: 5000 },
    distributions: { costUsdPerQuestion: { p50: 0.002 } },
    failureCounts: { contradictionsMissed: 2, completionErrors: 1, repeatedQuestions: 0, earlyCompletions: 0 },
    deterministicFailures: 2,
    semanticGateFailures: 1,
    continuityGateFailures: 0,
    reviewer: { invocations: 0, eligibleTurns: 10, cadence: 0 },
  };
  const reviewed = {
    usefulEvidencePerQuestion: 0.6,
    latencyMs: { p95: 8000 },
    distributions: { costUsdPerQuestion: { p50: 0.003 } },
    failureCounts: { contradictionsMissed: 0, completionErrors: 0, repeatedQuestions: 0, earlyCompletions: 0 },
    deterministicFailures: 0,
    semanticGateFailures: 0,
    continuityGateFailures: 0,
    reviewer: { invocations: 4, eligibleTurns: 10, cadence: 0.4 },
  };

  const comparison = compareReviewStrategies({ baseline, reviewed });

  assert.equal(comparison.usefulEvidencePerQuestionDelta, 0.6 - 0.5);
  assert.equal(comparison.missedConflictDelta, -2);
  assert.equal(comparison.completionErrorDelta, -1);
  assert.equal(comparison.costPerQuestionRatio, 1.5);
  assert.equal(comparison.p95LatencyRatio, 1.6);
  assert.deepEqual(comparison.reviewerCadence, { invocations: 4, eligibleTurns: 10, rate: 0.4 });
  assert.equal(comparison.decision, "GO");
});

test("review is a no-go without a reliability gain or when safety regresses", () => {
  const baseline = {
    usefulEvidencePerQuestion: 0.5,
    latencyMs: { p95: 5000 },
    distributions: { costUsdPerQuestion: { p50: 0.002 } },
    failureCounts: { contradictionsMissed: 0, completionErrors: 0, repeatedQuestions: 0, earlyCompletions: 0 },
    deterministicFailures: 0,
    semanticGateFailures: 0,
    continuityGateFailures: 0,
    reviewer: { invocations: 0, eligibleTurns: 10, cadence: 0 },
  };
  const reviewed = structuredClone(baseline);
  reviewed.reviewer = { invocations: 3, eligibleTurns: 10, cadence: 0.3 };
  assert.equal(compareReviewStrategies({ baseline, reviewed }).decision, "NO_GO");

  reviewed.failureCounts.earlyCompletions = 1;
  reviewed.usefulEvidencePerQuestion = 0.8;
  assert.equal(compareReviewStrategies({ baseline, reviewed }).decision, "NO_GO");
});
