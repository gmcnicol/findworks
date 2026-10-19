import assert from "node:assert/strict";
import test from "node:test";
import { buildPeriodicReviewReport, formatPeriodicReviewSummary, parsePeriodicReviewOptions } from "./periodic-review.mjs";

const root = new URL("../../", import.meta.url).pathname;
const modelIdentity = {
  provider: "openai",
  requestedModel: "gpt-5.4-mini",
  responseModel: "gpt-5.4-mini-2026-03-17",
  reasoningEffort: "low",
};

function summary(overrides = {}) {
  return {
    scenarios: 1,
    runs: 1,
    turns: 2,
    deterministicFailures: 1,
    semanticGateFailures: 1,
    continuityGateFailures: 0,
    usefulEvidencePerQuestion: 0.5,
    latencyMs: { p95: 5000 },
    distributions: { costUsdPerQuestion: { p50: 0.002 } },
    failureCounts: { contradictionsMissed: 1, completionErrors: 1, repeatedQuestions: 0, earlyCompletions: 0 },
    reviewer: { invocations: 0, eligibleTurns: 2, cadence: 0, triggers: {} },
    ...overrides,
  };
}

test("paired options pin the same model, reasoning, fixtures, repeats, runtime, and output", () => {
  const options = parsePeriodicReviewOptions([
    "--image", "findworks-runtime:67",
    "--commit", "abc",
    "--model", "gpt-5.4-mini",
    "--reasoning", "low",
    "--repeats", "2",
    "--output", "/tmp/review",
  ], {}, root);
  assert.equal(options.image, "findworks-runtime:67");
  assert.equal(options.commit, "abc");
  assert.equal(options.model, "gpt-5.4-mini");
  assert.equal(options.reasoningEffort, "low");
  assert.equal(options.repeats, 2);
  assert.equal(options.outputDirectory, "/tmp/review");
  assert.match(options.fixturesDirectory, /fixtures\/v1$/);
});

test("paired report retains observed role identities and makes a thresholded decision", () => {
  const baseline = {
    metadata: { model: "openai/gpt-5.4-mini", reasoningEffort: "low" },
    summary: summary(),
    runs: [{ turns: [{ usage: { modelIdentity } }] }],
  };
  const reviewed = {
    metadata: { model: "openai/gpt-5.4-mini", reasoningEffort: "low" },
    summary: summary({
      deterministicFailures: 0,
      semanticGateFailures: 0,
      usefulEvidencePerQuestion: 0.75,
      latencyMs: { p95: 7500 },
      distributions: { costUsdPerQuestion: { p50: 0.003 } },
      failureCounts: { contradictionsMissed: 0, completionErrors: 0, repeatedQuestions: 0, earlyCompletions: 0 },
      reviewer: { invocations: 1, eligibleTurns: 2, cadence: 0.5, triggers: { INITIAL_EVIDENCE: 1 } },
    }),
    runs: [{ turns: [{
      interviewerUsage: { modelIdentity },
      reviewerUsage: { modelIdentity },
      trace: { review: { executionSucceeded: true } },
    }] }],
  };
  const report = buildPeriodicReviewReport({
    metadata: {
      commit: "abc", runtimeVersion: "0.84.4", runtimeImageId: "sha256:image",
      provider: "openai", requestedModel: "gpt-5.4-mini", reasoningEffort: "low",
      fixtureVersions: { fixture: "1.0.0" }, repeats: 1,
    },
    baseline,
    reviewed,
  });

  assert.equal(report.comparison.decision, "GO");
  assert.equal(report.comparison.usefulEvidencePerQuestionDelta, 0.25);
  assert.equal(report.comparison.reviewerCadence.rate, 0.5);
  assert.deepEqual(report.metadata.actualModelIdentities, {
    baselineInterviewer: { successfulTurns: 1, identity: modelIdentity },
    reviewedInterviewer: { successfulTurns: 1, identity: modelIdentity },
    reviewer: { successfulTurns: 1, identity: modelIdentity },
  });
  assert.deepEqual(report.baseline.metadata.actualModelIdentities, {
    interviewer: { successfulTurns: 1, identity: modelIdentity },
  });
  assert.deepEqual(report.reviewed.metadata.actualModelIdentities, {
    interviewer: { successfulTurns: 1, identity: modelIdentity },
    reviewer: { successfulTurns: 1, identity: modelIdentity },
  });
  const markdown = formatPeriodicReviewSummary(report);
  assert.match(markdown, /Decision: \*\*GO\*\*/);
  assert.match(markdown, /Useful Evidence \/ Question/);
  assert.match(markdown, /Missed conflicts/);
  assert.match(markdown, /Completion errors/);
  assert.match(markdown, /Reviewer cadence/);
  assert.match(markdown, /gpt-5\.4-mini-2026-03-17/);
  assert.match(markdown, /0\.84\.4/);
});

test("paired report rejects role alias drift and role substitution", () => {
  const createInput = reviewerIdentity => ({
    metadata: {
      provider: "openai", requestedModel: "gpt-5.4-mini", reasoningEffort: "low",
    },
    baseline: {
      metadata: { model: "openai/gpt-5.4-mini", reasoningEffort: "low" },
      summary: summary(),
      runs: [{ turns: [{ usage: { modelIdentity } }] }],
    },
    reviewed: {
      metadata: { model: "openai/gpt-5.4-mini", reasoningEffort: "low" },
      summary: summary(),
      runs: [{ turns: [{
        interviewerUsage: { modelIdentity },
        reviewerUsage: { modelIdentity: reviewerIdentity },
        trace: { review: { executionSucceeded: true } },
      }] }],
    },
  });

  assert.throws(() => buildPeriodicReviewReport(createInput({
    ...modelIdentity,
    responseModel: "gpt-5.4-mini-new-alias-target",
  })), /same observed model identity/);
  assert.throws(() => buildPeriodicReviewReport(createInput({
    ...modelIdentity,
    requestedModel: "gpt-5.4",
  })), /does not match configured requestedModel/);
});

test("paired report rejects missing baseline interviewer reviewed interviewer and reviewer identities", () => {
  function input() {
    return {
      metadata: { provider: "openai", requestedModel: "gpt-5.4-mini", reasoningEffort: "low" },
      baseline: {
        metadata: { model: "openai/gpt-5.4-mini", reasoningEffort: "low" },
        summary: summary(),
        runs: [{ turns: [{ usage: { modelIdentity } }] }],
      },
      reviewed: {
        metadata: { model: "openai/gpt-5.4-mini", reasoningEffort: "low" },
        summary: summary(),
        runs: [{ turns: [{
          interviewerUsage: { modelIdentity },
          reviewerUsage: { modelIdentity },
          trace: { review: { executionSucceeded: true } },
        }] }],
      },
    };
  }

  const missingBaseline = input();
  missingBaseline.baseline.runs[0].turns[0].usage = null;
  assert.throws(() => buildPeriodicReviewReport(missingBaseline), /Baseline interviewer.*not captured/);

  const missingInterviewer = input();
  missingInterviewer.reviewed.runs[0].turns[0].interviewerUsage = { inputTokens: 1 };
  assert.throws(() => buildPeriodicReviewReport(missingInterviewer), /Reviewed interviewer.*not captured/);

  const missingReviewer = input();
  missingReviewer.reviewed.runs[0].turns[0].reviewerUsage = null;
  assert.throws(() => buildPeriodicReviewReport(missingReviewer), /Reviewer.*not captured/);
});
