import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  buildBenchmarkReport,
  formatBenchmarkSummary,
  parseBenchmarkOptions,
  probeOpenAiConfigurations,
  runBenchmark,
} from "./benchmark.mjs";

test("provider preflight verifies exact model access and reasoning support without retaining provider identifiers", async () => {
  const requests = [];
  const fetchImpl = async (url, init = {}) => {
    requests.push({ url, init });
    if (url.endsWith("/models")) {
      return response(200, { data: [{ id: "gpt-5.4-mini" }] });
    }
    const request = JSON.parse(init.body);
    if (request.model === "gpt-5.4") {
      return response(403, {
        error: {
          code: "model_not_found",
          type: "invalid_request_error",
          message: "Project proj_secret does not have access to model gpt-5.4",
        },
      });
    }
    return response(200, { model: "gpt-5.4-mini-2026-03-17" });
  };

  const result = await probeOpenAiConfigurations([
    { id: "baseline", model: "gpt-5.4-mini", reasoningEffort: "low" },
    { id: "candidate", model: "gpt-5.4", reasoningEffort: "medium" },
  ], { apiKey: "sk-secret", fetchImpl });

  assert.deepEqual(result, [{
    configurationId: "baseline",
    requestedModel: "gpt-5.4-mini",
    reasoningEffort: "low",
    listed: true,
    accessible: true,
    reasoningSupported: true,
    resolvedModel: "gpt-5.4-mini-2026-03-17",
    httpStatus: 200,
    errorCode: null,
    errorType: null,
  }, {
    configurationId: "candidate",
    requestedModel: "gpt-5.4",
    reasoningEffort: "medium",
    listed: false,
    accessible: false,
    reasoningSupported: false,
    resolvedModel: null,
    httpStatus: 403,
    errorCode: "model_not_found",
    errorType: "invalid_request_error",
  }]);
  assert.equal(JSON.stringify(result).includes("proj_secret"), false);
  assert.equal(requests.filter(request => request.url.endsWith("/responses")).length, 2);
});

test("benchmark CLI defaults to three repeat trials and rejects a single trial", () => {
  const options = parseBenchmarkOptions(["--image", "runtime:test", "--output", "/tmp/benchmark"], {}, "/repo");
  assert.equal(options.image, "runtime:test");
  assert.equal(options.repeats, 3);
  assert.equal(options.outputDirectory, "/tmp/benchmark");
  assert.throws(() => parseBenchmarkOptions(["--repeats", "1"], {}, "/repo"), /at least 2/);
});

test("benchmark runner probes then evaluates every accessible configuration with identical repeats and fixtures", async () => {
  const directory = await mkdtemp(join(tmpdir(), "findworks-benchmark-"));
  const configurationsPath = join(directory, "configurations.json");
  const outputDirectory = join(directory, "output");
  await writeFile(configurationsPath, JSON.stringify({
    schemaVersion: 1,
    configurationVersion: "1.0.0",
    configurations: [
      { id: "mini-low", label: "Mini low", provider: "openai", model: "gpt-5.4-mini", reasoningEffort: "low", required: true },
      { id: "full-medium", label: "Full medium", provider: "openai", model: "gpt-5.4", reasoningEffort: "medium", required: true },
    ],
  }));
  const calls = [];
  const report = await runBenchmark({
    image: "runtime:test",
    repeats: 2,
    outputDirectory,
    configurationsPath,
    fixturesDirectory: "/fixtures/v1",
    commit: "abc123",
    root: "/repo",
  }, {
    env: { OPENAI_API_KEY: "secret" },
    resolveCommit: () => "abc123",
    workingTreeDirty: false,
    probeConfigurations: async configurations => configurations.map(configuration => probe(configuration.id, true)),
    executeEvaluation: async (configuration, options) => {
      calls.push({ configuration, options });
      return evaluationReport({ semanticP25: 0.8, semanticP50: 0.9, evidenceP50: 0.8, costPerQuestion: 0.01 });
    },
  });

  assert.equal(calls.length, 2);
  assert.ok(calls.every(call => call.options.repeats === 2 && call.options.fixturesDirectory === "/fixtures/v1"));
  assert.equal(report.metadata.configurationVersion, "1.0.0");
  assert.deepEqual(JSON.parse(await readFile(join(outputDirectory, "benchmark.json"), "utf8")), report);
  assert.match(await readFile(join(outputDirectory, "summary.md"), "utf8"), /Adaptive Interview Model Benchmark/);
});

test("benchmark report retains sanitised failures, distributions, trade-offs, criterion, and a conservative recommendation", () => {
  const baseline = evaluationReport({ semanticP25: 0.8, semanticP50: 0.9, evidenceP50: 0.8, costPerQuestion: 0.01 });
  baseline.runs[0].turns[0] = {
    trace: { turn: 1, sourceAnswer: { text: "Synthetic answer" }, action: { type: "ASK_QUESTION", text: "Repeat?" } },
    contractViolations: [{ rule: "runtime-execution", message: "Bearer sk-secret failed for proj_private", sourceAnswer: "Synthetic answer", offendingAction: null }],
    semanticQuality: { checks: [{ rule: "expected-outcome:CONFLICT", passed: false, detail: "Missing CONFLICT" }] },
  };
  const optional = evaluationReport({ semanticP25: 0.82, semanticP50: 0.91, evidenceP50: 0.8, costPerQuestion: 0.012 });
  const configurations = [
    { id: "mini-low", label: "GPT-5.4 mini / low", model: "gpt-5.4-mini", reasoningEffort: "low", required: true },
    { id: "gpt-medium", label: "GPT-5.4 / medium", model: "gpt-5.4", reasoningEffort: "medium", required: true },
    { id: "mini-medium", label: "GPT-5.4 mini / medium", model: "gpt-5.4-mini", reasoningEffort: "medium", required: false },
  ];
  const probes = [
    probe("mini-low", true),
    probe("gpt-medium", false),
    probe("mini-medium", true),
  ];

  const report = buildBenchmarkReport({
    metadata: { commit: "abc123", repeats: 3 },
    configurations,
    probes,
    evaluationReports: new Map([["mini-low", baseline], ["mini-medium", optional]]),
  });

  assert.equal(report.configurations.find(value => value.id === "gpt-medium").status, "blocked");
  assert.equal(report.configurations.find(value => value.id === "mini-low").individualFailures.length, 2);
  assert.equal(JSON.stringify(report).includes("sk-secret"), false);
  assert.equal(JSON.stringify(report).includes("proj_private"), false);
  assert.equal(report.tradeoffs.find(value => value.id === "mini-medium").costPerQuestionRatio, 1.2);
  assert.equal(report.promotionCriterion.minimumLowerQuartileSemanticGain, 0.05);
  assert.equal(report.recommendation.configurationId, "mini-low");
  assert.match(report.recommendation.reason, /Retain/);
  assert.ok(report.blockers.some(value => value.configurationId === "gpt-medium"));
  const markdown = formatBenchmarkSummary(report);
  assert.match(markdown, /Provider verification/);
  assert.match(markdown, /Promotion criterion/);
  assert.match(markdown, /Individual failures/);
  assert.match(markdown, /Retain GPT-5\.4 mini \/ low/);
});

function evaluationReport({ semanticP25, semanticP50, evidenceP50, costPerQuestion }) {
  return {
    metadata: { fixtureVersions: { synthetic: "1.0.0" }, costUsd: costPerQuestion * 10 },
    summary: {
      deterministicFailures: 0,
      semanticGateFailures: 0,
      continuityGateFailures: 0,
      semanticPassed: 9,
      semanticTotal: 10,
      distributions: {
        semanticPassRate: { p25: semanticP25, p50: semanticP50 },
        usefulEvidencePerQuestion: { p50: evidenceP50 },
        costUsdPerQuestion: { p50: costPerQuestion },
        latencyMs: { p95: 1000 },
      },
      failureCounts: { contradictionsMissed: 0, repeatedQuestions: 0, earlyCompletions: 0 },
    },
    runs: [{ scenarioId: "synthetic", repeat: 1, turns: [{ contractViolations: [], semanticQuality: { checks: [] }, trace: { turn: 1 } }] }],
  };
}

function probe(configurationId, accessible) {
  return {
    configurationId,
    requestedModel: configurationId === "gpt-medium" ? "gpt-5.4" : "gpt-5.4-mini",
    reasoningEffort: configurationId.endsWith("medium") ? "medium" : "low",
    listed: accessible,
    accessible,
    reasoningSupported: accessible,
    resolvedModel: accessible ? "resolved-model" : null,
    httpStatus: accessible ? 200 : 403,
    errorCode: accessible ? null : "model_not_found",
    errorType: accessible ? null : "invalid_request_error",
  };
}

function response(status, body) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}
