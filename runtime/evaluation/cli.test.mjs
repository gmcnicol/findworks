import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { loadFixtures } from "./harness.mjs";
import {
  parseOptions,
  parseRuntimeMetrics,
  selectedProviderCredential,
  validateBaselineCompatibility,
  validateRunModelIdentity,
  writeReport,
} from "./cli.mjs";

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

test("evaluation containers receive only the selected provider credential", () => {
  const ambient = {
    OPENAI_API_KEY: "openai-secret",
    ANTHROPIC_API_KEY: "anthropic-secret",
    GEMINI_API_KEY: "gemini-secret",
  };

  assert.deepEqual(selectedProviderCredential("openai", ambient), { name: "OPENAI_API_KEY", kind: "api-key" });
  assert.deepEqual(selectedProviderCredential("anthropic", ambient), { name: "ANTHROPIC_API_KEY", kind: "api-key" });
  assert.deepEqual(selectedProviderCredential("google", ambient), { name: "GEMINI_API_KEY", kind: "api-key" });
  assert.throws(() => selectedProviderCredential("openai", {
    ...ambient,
    FINDWORKS_MODEL_OAUTH_B64: "oauth-secret",
  }), /multiple credentials/i);
  assert.throws(() => selectedProviderCredential("unknown", ambient), /unsupported provider/i);
});

test("runtime metrics parser accepts only the content-free metrics envelope", () => {
  assert.deepEqual(parseRuntimeMetrics('{"findworksRuntimeMetrics":{"inputTokens":10,"outputTokens":2,"cacheWriteTokens":1,"costUsd":0.01,"modelIdentity":{"provider":"openai","requestedModel":"gpt-5.4-mini","responseModel":"gpt-5.4-mini-2026-03-17","reasoningEffort":"low"}}}\n'), {
    inputTokens: 10,
    outputTokens: 2,
    cacheWriteTokens: 1,
    costUsd: 0.01,
    modelIdentity: {
      provider: "openai",
      requestedModel: "gpt-5.4-mini",
      responseModel: "gpt-5.4-mini-2026-03-17",
      reasoningEffort: "low",
    },
  });
  assert.equal(parseRuntimeMetrics('{"message":"interview content"}\n'), null);
});

test("baseline compatibility pins schema, fixtures, runtime, and exact model configuration", () => {
  const metadata = {
    commit: "current-commit",
    runtimeCommit: "current-commit",
    provider: "openai",
    model: "openai/gpt-5.4-mini",
    resolvedModel: "gpt-5.4-mini-2026-03-17",
    reasoningEffort: "low",
    credentialKind: "api-key",
    credentialEnvironment: "OPENAI_API_KEY",
    runtimeVersion: "0.84.4",
    fixtureSchemaVersion: 1,
    fixtureVersions: { alpha: "1.0.0", beta: "2.0.0" },
  };
  const baseline = { schemaVersion: 1, metadata: structuredClone(metadata), summary: {} };
  assert.doesNotThrow(() => validateBaselineCompatibility(baseline, metadata));
  for (const incompatible of [
    { schemaVersion: 2 },
    { metadata: { fixtureVersions: { alpha: "1.0.0" } } },
    { metadata: { runtimeVersion: "0.83.0" } },
    { metadata: { resolvedModel: "different-model" } },
  ]) {
    const changed = structuredClone(baseline);
    if (incompatible.schemaVersion) changed.schemaVersion = incompatible.schemaVersion;
    Object.assign(changed.metadata, incompatible.metadata ?? {});
    assert.throws(() => validateBaselineCompatibility(changed, metadata), /baseline/i);
  }
});

test("checked-in baseline is retained credentialed evidence for the current fixture portfolio", async () => {
  const baseline = JSON.parse(await readFile(join(root, "runtime/evaluation/baselines/gpt-5.4-mini-low.json"), "utf8"));
  const fixtures = await loadFixtures(join(root, "runtime/evaluation/fixtures/v1"));
  validateBaselineCompatibility(baseline, {
    commit: "current-commit",
    runtimeCommit: "current-commit",
    provider: "openai",
    model: "openai/gpt-5.4-mini",
    resolvedModel: "gpt-5.4-mini-2026-03-17",
    reasoningEffort: "low",
    credentialKind: "api-key",
    credentialEnvironment: "OPENAI_API_KEY",
    runtimeVersion: "0.84.4",
    fixtureSchemaVersion: 1,
    fixtureVersions: Object.fromEntries(fixtures.map(value => [value.id, value.fixtureVersion])),
  });
  assert.match(baseline.metadata.source, /docs\/acceptance\/traces\/issue-67/);
  assert.doesNotMatch(baseline.metadata.limitations, /replace/i);
});

test("actual Runtime Turns retain exact model identity and configured Pi reasoning", () => {
  const identity = {
    provider: "openai",
    requestedModel: "gpt-5.4-mini",
    responseModel: "gpt-5.4-mini-2026-03-17",
    reasoningEffort: "low",
  };
  const runs = [{ turns: [{ usage: { modelIdentity: identity } }, { usage: { modelIdentity: identity } }] }];
  assert.deepEqual(validateRunModelIdentity(runs, identity), identity);
  assert.throws(() => validateRunModelIdentity(runs, { ...identity, responseModel: "different-model" }), /does not match preflight/);
  assert.throws(() => validateRunModelIdentity([
    { turns: [{ usage: { modelIdentity: identity } }, { usage: { modelIdentity: { ...identity, reasoningEffort: "medium" } } }] },
  ], identity), /more than one model or reasoning identity/);
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
