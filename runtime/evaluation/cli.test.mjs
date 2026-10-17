import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { parseOptions, parseRuntimeMetrics, writeReport } from "./cli.mjs";

const root = new URL("../../", import.meta.url).pathname;

test("CLI options pin image, commit, model, reasoning effort, repeats, and scenario", () => {
  const options = parseOptions([
    "--image", "findworks-runtime:evaluation",
    "--commit", "abc123",
    "--provider", "openai",
    "--model", "gpt-5.4-mini",
    "--reasoning", "medium",
    "--repeats", "3",
    "--scenario", "morning-sort",
    "--output", "/tmp/evaluation",
  ], {}, root);

  assert.equal(options.image, "findworks-runtime:evaluation");
  assert.equal(options.commit, "abc123");
  assert.equal(options.model, "gpt-5.4-mini");
  assert.equal(options.reasoningEffort, "medium");
  assert.equal(options.repeats, 3);
  assert.deepEqual(options.scenarioIds, ["morning-sort"]);
  assert.equal(options.outputDirectory, "/tmp/evaluation");
});

test("CLI options retain environment compatibility with the original acceptance command", () => {
  const options = parseOptions([], {
    FINDWORKS_RUNTIME_IMAGE: "runtime:old-command",
    FINDWORKS_MODEL_PROVIDER: "openai",
    FINDWORKS_MODEL: "gpt-5.4-mini",
    FINDWORKS_REASONING_EFFORT: "low",
    FINDWORKS_EVALUATION_REPEATS: "2",
  }, root);
  assert.equal(options.image, "runtime:old-command");
  assert.equal(options.repeats, 2);
  assert.match(options.fixturesDirectory, /runtime\/evaluation\/fixtures\/v1$/);
});

test("runtime metrics parser accepts only the content-free metrics envelope", () => {
  assert.deepEqual(parseRuntimeMetrics('{"findworksRuntimeMetrics":{"inputTokens":10,"outputTokens":2,"cacheWriteTokens":1,"costUsd":0.01}}\n'), {
    inputTokens: 10,
    outputTokens: 2,
    cacheWriteTokens: 1,
    costUsd: 0.01,
  });
  assert.equal(parseRuntimeMetrics('{"message":"interview content"}\n'), null);
});

test("report writer always emits machine-readable JSON and readable Markdown", async () => {
  const directory = await mkdtemp(join(tmpdir(), "findworks-report-"));
  const report = {
    schemaVersion: 1,
    metadata: { commit: "abc", model: "openai/gpt", reasoningEffort: "low" },
    summary: {
      scenarios: 1, runs: 1, turns: 1, deterministicPasses: 1, deterministicFailures: 0,
      semanticPassed: 1, semanticTotal: 1, semanticPassRate: 1, durationMs: 10,
    },
    comparison: null,
    runs: [{ scenarioId: "scenario", repeat: 1, turns: [] }],
  };
  const paths = await writeReport(report, directory);
  assert.deepEqual(JSON.parse(await readFile(paths.json, "utf8")), report);
  assert.match(await readFile(paths.markdown, "utf8"), /Adaptive Interview Evaluation/);
});
