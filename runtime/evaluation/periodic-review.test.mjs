import assert from "node:assert/strict";
import test from "node:test";
import { buildPeriodicReviewReport, formatPeriodicReviewSummary, parsePeriodicReviewOptions } from "./periodic-review.mjs";

const root = new URL("../../", import.meta.url).pathname;

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

test("paired report retains exact metadata and makes a thresholded decision", () => {
  const baseline = { metadata: { model: "openai/gpt-5.4-mini", reasoningEffort: "low" }, summary: summary(), runs: [] };
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
    runs: [],
  };
  const report = buildPeriodicReviewReport({
    metadata: {
      commit: "abc", runtimeVersion: "0.84.4", runtimeImageId: "sha256:image",
      fixtureVersions: { fixture: "1.0.0" }, repeats: 1,
    },
    baseline,
    reviewed,
  });

  assert.equal(report.comparison.decision, "GO");
  assert.equal(report.comparison.usefulEvidencePerQuestionDelta, 0.25);
  assert.equal(report.comparison.reviewerCadence.rate, 0.5);
  const markdown = formatPeriodicReviewSummary(report);
  assert.match(markdown, /Decision: \*\*GO\*\*/);
  assert.match(markdown, /Useful Evidence \/ Question/);
  assert.match(markdown, /Missed conflicts/);
  assert.match(markdown, /Completion errors/);
  assert.match(markdown, /Reviewer cadence/);
  assert.match(markdown, /gpt-5\.4-mini/);
  assert.match(markdown, /0\.84\.4/);
});
