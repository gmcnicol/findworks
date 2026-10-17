import { spawn } from "node:child_process";
import { execFileSync } from "node:child_process";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const OPENAI_API_BASE = "https://api.openai.com/v1";
const moduleDirectory = dirname(fileURLToPath(import.meta.url));
const defaultRoot = resolve(moduleDirectory, "../..");

export const PROMOTION_CRITERION = Object.freeze({
  zeroDeterministicFailures: true,
  zeroSemanticGateFailures: true,
  zeroContinuityGateFailures: true,
  zeroMissedContradictions: true,
  zeroRepeatedQuestions: true,
  zeroEarlyCompletions: true,
  minimumLowerQuartileSemanticGain: 0.05,
  maximumMedianEvidenceYieldRegression: 0.02,
  maximumMedianCostPerUsefulEvidenceRatio: 2,
  maximumP95LatencyRatio: 2,
});

export function parseBenchmarkOptions(argv, env = process.env, root = defaultRoot) {
  const options = {
    image: env.FINDWORKS_RUNTIME_IMAGE,
    repeats: minimumInteger(env.FINDWORKS_BENCHMARK_REPEATS, 3, 2),
    outputDirectory: env.FINDWORKS_BENCHMARK_OUTPUT || join(root, "artifacts/adaptive-interview-model-benchmark", timestamp()),
    configurationsPath: join(root, "runtime/evaluation/benchmark-configurations.v1.json"),
    fixturesDirectory: join(root, "runtime/evaluation/fixtures/v1"),
    commit: env.FINDWORKS_EVALUATION_COMMIT,
    root,
  };
  for (let index = 0; index < argv.length; index++) {
    const flag = argv[index];
    if (flag === "--help" || flag === "-h") { options.help = true; continue; }
    const value = argv[++index];
    if (value === undefined) throw new Error(`${flag} requires a value`);
    switch (flag) {
      case "--image": options.image = value; break;
      case "--repeats": options.repeats = minimumInteger(value, undefined, 2); break;
      case "--output": options.outputDirectory = resolve(value); break;
      case "--configurations": options.configurationsPath = resolve(value); break;
      case "--fixtures": options.fixturesDirectory = resolve(value); break;
      case "--commit": options.commit = value; break;
      default: throw new Error(`Unknown option: ${flag}`);
    }
  }
  return options;
}

export async function runBenchmark(options, dependencies = {}) {
  const env = dependencies.env ?? process.env;
  const resolvePinnedCommit = dependencies.resolveCommit ?? resolveCommit;
  const probeConfigurations = dependencies.probeConfigurations
    ?? (configurations => probeOpenAiConfigurations(configurations, { apiKey: env.OPENAI_API_KEY }));
  const executeEvaluation = dependencies.executeEvaluation ?? executeModelEvaluation;
  const workingTreeDirty = dependencies.workingTreeDirty
    ?? (() => git(options.root, ["status", "--porcelain"]).trim() !== "");
  const commit = resolvePinnedCommit(options.root, options.commit);
  const manifest = JSON.parse(await readFile(options.configurationsPath, "utf8"));
  validateConfigurationManifest(manifest);
  const configurations = manifest.configurations;
  const startedAt = new Date().toISOString();
  const probes = await probeConfigurations(configurations);
  const probeById = new Map(probes.map(probe => [probe.configurationId, probe]));
  const evaluationReports = new Map();
  for (const configuration of configurations) {
    const probe = probeById.get(configuration.id);
    if (!probe?.accessible || !probe.reasoningSupported) continue;
    const report = await executeEvaluation(configuration, { ...options, commit }, env);
    if (report) evaluationReports.set(configuration.id, report);
  }
  const report = buildBenchmarkReport({
    metadata: {
      startedAt,
      completedAt: new Date().toISOString(),
      commit,
      workingTreeDirty: typeof workingTreeDirty === "function" ? workingTreeDirty() : workingTreeDirty,
      runtimeImage: options.image,
      repeats: options.repeats,
      configurationVersion: manifest.configurationVersion,
      configurationSchemaVersion: manifest.schemaVersion,
      fixturesDirectory: options.fixturesDirectory,
    },
    configurations,
    probes,
    evaluationReports,
  });
  await writeBenchmarkReport(report, options.outputDirectory);
  return report;
}

export async function runBenchmarkCli(argv = process.argv.slice(2), env = process.env) {
  const options = parseBenchmarkOptions(argv, env);
  if (options.help) {
    process.stdout.write(benchmarkHelp());
    return 0;
  }
  if (!options.image) throw new Error("Set FINDWORKS_RUNTIME_IMAGE or pass --image");
  const report = await runBenchmark(options, { env });
  process.stdout.write(formatBenchmarkSummary(report));
  process.stdout.write(`JSON: ${join(options.outputDirectory, "benchmark.json")}\nSummary: ${join(options.outputDirectory, "summary.md")}\n`);
  const qualityFailure = report.configurations.some(configuration => configuration.status === "completed"
    && (configuration.summary.deterministicFailures > 0
      || configuration.summary.semanticGateFailures > 0
      || configuration.summary.continuityGateFailures > 0));
  return report.blockers.length > 0 || qualityFailure ? 1 : 0;
}

export async function writeBenchmarkReport(report, outputDirectory) {
  await mkdir(outputDirectory, { recursive: true });
  await writeFile(join(outputDirectory, "benchmark.json"), `${JSON.stringify(report, null, 2)}\n`);
  await writeFile(join(outputDirectory, "summary.md"), formatBenchmarkSummary(report));
}

export async function probeOpenAiConfigurations(configurations, { apiKey, fetchImpl = fetch }) {
  if (!apiKey) throw new Error("OPENAI_API_KEY is required for provider capability verification");
  const modelResponse = await fetchImpl(`${OPENAI_API_BASE}/models`, {
    headers: { authorization: `Bearer ${apiKey}` },
  });
  const modelBody = await modelResponse.json();
  if (!modelResponse.ok) throw new Error(`OpenAI model discovery failed with HTTP ${modelResponse.status}`);
  const listedModels = new Set((modelBody.data ?? []).map(model => model.id));
  const results = [];
  for (const configuration of configurations) {
    const response = await fetchImpl(`${OPENAI_API_BASE}/responses`, {
      method: "POST",
      headers: { authorization: `Bearer ${apiKey}`, "content-type": "application/json" },
      body: JSON.stringify({
        model: configuration.model,
        reasoning: { effort: configuration.reasoningEffort },
        input: "Reply with OK.",
        max_output_tokens: 32,
      }),
    });
    const body = await response.json();
    results.push({
      configurationId: configuration.id,
      requestedModel: configuration.model,
      reasoningEffort: configuration.reasoningEffort,
      listed: listedModels.has(configuration.model),
      accessible: response.ok,
      reasoningSupported: response.ok,
      resolvedModel: response.ok ? body.model ?? null : null,
      httpStatus: response.status,
      errorCode: response.ok ? null : body.error?.code ?? "provider_error",
      errorType: response.ok ? null : body.error?.type ?? null,
    });
  }
  return results;
}

export function buildBenchmarkReport({ metadata, configurations, probes, evaluationReports }) {
  const probeById = new Map(probes.map(probe => [probe.configurationId, probe]));
  const baselineConfiguration = configurations[0];
  const baselineEvaluation = evaluationReports.get(baselineConfiguration.id);
  const baselineTradeoff = baselineEvaluation ? tradeoffMetrics(baselineEvaluation.summary) : null;
  const blockers = [];
  const results = configurations.map(configuration => {
    const probe = probeById.get(configuration.id) ?? null;
    const evaluation = evaluationReports.get(configuration.id) ?? null;
    let status = "completed";
    if (!probe?.accessible || !probe?.reasoningSupported) status = "blocked";
    else if (!evaluation) status = "failed";
    if (status !== "completed" && configuration.required) {
      blockers.push({
        configurationId: configuration.id,
        reason: status === "blocked"
          ? `Provider access or reasoning support was not verified (${probe?.errorCode ?? "no_probe"})`
          : "Evaluation did not produce a report",
      });
    }
    return {
      ...configuration,
      status,
      providerVerification: probe,
      fixtureVersions: evaluation?.metadata?.fixtureVersions ?? null,
      summary: evaluation?.summary ?? null,
      individualFailures: evaluation ? extractIndividualFailures(evaluation.runs ?? []) : [],
    };
  });
  const baselineFixtureVersions = results[0]?.fixtureVersions;
  for (const result of results.slice(1)) {
    if (result.status === "completed" && JSON.stringify(result.fixtureVersions) !== JSON.stringify(baselineFixtureVersions)) {
      result.status = "invalid";
      blockers.push({ configurationId: result.id, reason: "Fixture versions differ from the baseline" });
    }
  }
  const tradeoffs = results.map(result => {
    if (!result.summary || result.status !== "completed") return { id: result.id, status: result.status };
    const metrics = tradeoffMetrics(result.summary);
    return {
      id: result.id,
      status: result.status,
      ...metrics,
      semanticLowerQuartileDelta: difference(metrics.semanticPassRateP25, baselineTradeoff?.semanticPassRateP25),
      semanticMedianDelta: difference(metrics.semanticPassRateP50, baselineTradeoff?.semanticPassRateP50),
      evidenceYieldMedianDelta: difference(metrics.usefulEvidencePerQuestionP50, baselineTradeoff?.usefulEvidencePerQuestionP50),
      costPerQuestionRatio: ratio(metrics.costUsdPerQuestionP50, baselineTradeoff?.costUsdPerQuestionP50),
      costPerUsefulEvidenceRatio: ratio(metrics.costUsdPerUsefulEvidence, baselineTradeoff?.costUsdPerUsefulEvidence),
      p95LatencyRatio: ratio(metrics.latencyMsP95, baselineTradeoff?.latencyMsP95),
    };
  });
  const eligible = results.slice(1).filter(result => result.status === "completed")
    .map(result => ({ result, tradeoff: tradeoffs.find(value => value.id === result.id) }))
    .filter(({ result, tradeoff }) => meetsPromotionCriterion(result.summary, tradeoff));
  const promoted = eligible.sort((left, right) =>
    (right.tradeoff.semanticPassRateP50 ?? 0) - (left.tradeoff.semanticPassRateP50 ?? 0))[0];
  const recommendation = promoted ? {
    configurationId: promoted.result.id,
    decision: "promote",
    reason: `Promote ${promoted.result.label}: it meets every safety, quality, cost, and latency criterion.`,
  } : {
    configurationId: baselineConfiguration.id,
    decision: "retain",
    reason: `Retain ${baselineConfiguration.label}: no candidate met every promotion criterion.`,
  };
  return {
    schemaVersion: 1,
    metadata,
    promotionCriterion: PROMOTION_CRITERION,
    configurations: results,
    tradeoffs,
    recommendation,
    blockers,
  };
}

export function formatBenchmarkSummary(report) {
  const lines = [
    "# Adaptive Interview Model Benchmark",
    "",
    `- Commit: \`${report.metadata.commit}\``,
    `- Repeats per configuration: ${report.metadata.repeats}`,
    `- Recommendation: **${report.recommendation.reason}**`,
    "",
    "## Provider verification",
    "",
    "| Configuration | Requested | Resolved provider model | Listed | Actual reasoning probe | Status |",
    "| --- | --- | --- | --- | --- | --- |",
  ];
  for (const configuration of report.configurations) {
    const probe = configuration.providerVerification;
    lines.push(`| ${configuration.label} | \`${configuration.model}\` / \`${configuration.reasoningEffort}\` | \`${probe?.resolvedModel ?? "unavailable"}\` | ${yesNo(probe?.listed)} | ${yesNo(probe?.reasoningSupported)} | ${configuration.status} |`);
  }
  lines.push("", "## Aggregate distributions and trade-offs", "",
    "| Configuration | Semantic p25 / p50 | Evidence/question p50 | Turns to sufficient Evidence p50 | Cost/question p50 | Cost/useful Evidence | Latency p95 |",
    "| --- | --- | --- | --- | --- | --- | --- |");
  for (const tradeoff of report.tradeoffs) {
    if (tradeoff.status !== "completed") {
      lines.push(`| ${tradeoff.id} | ${tradeoff.status} | — | — | — | — | — |`);
      continue;
    }
    lines.push(`| ${tradeoff.id} | ${percent(tradeoff.semanticPassRateP25)} / ${percent(tradeoff.semanticPassRateP50)} | ${decimal(tradeoff.usefulEvidencePerQuestionP50)} | ${decimal(tradeoff.turnsToSufficientEvidenceP50)} | ${usd(tradeoff.costUsdPerQuestionP50)} | ${usd(tradeoff.costUsdPerUsefulEvidence)} | ${milliseconds(tradeoff.latencyMsP95)} |`);
  }
  lines.push("", "## Promotion criterion", "",
    `A candidate promotes only with zero contract, semantic-gate, continuity, missed-contradiction, repeated-question, and early-completion failures; at least ${percent(report.promotionCriterion.minimumLowerQuartileSemanticGain)} lower-quartile semantic gain; no more than ${percent(report.promotionCriterion.maximumMedianEvidenceYieldRegression)} median Evidence-yield regression; no more than ${report.promotionCriterion.maximumMedianCostPerUsefulEvidenceRatio}× median cost per useful Evidence; and no more than ${report.promotionCriterion.maximumP95LatencyRatio}× p95 latency.`,
    "", "## Individual failures", "");
  let failureCount = 0;
  for (const configuration of report.configurations) {
    for (const failure of configuration.individualFailures) {
      failureCount += 1;
      lines.push(`- **${configuration.id} / ${failure.scenarioId} / repeat ${failure.repeat} / turn ${failure.turn} / ${failure.kind} ${failure.rule}:** ${failure.detail}`);
      if (failure.sourceAnswer) lines.push(`  - Synthetic source answer: ${String(failure.sourceAnswer)}`);
      if (failure.offendingAction) lines.push(`  - Offending action: \`${JSON.stringify(failure.offendingAction)}\``);
    }
  }
  if (failureCount === 0) lines.push("- None.");
  lines.push("", "## Blockers", "");
  if (report.blockers.length === 0) lines.push("- None.");
  else for (const blocker of report.blockers) lines.push(`- **${blocker.configurationId}:** ${blocker.reason}`);
  return `${lines.join("\n")}\n`;
}

async function executeModelEvaluation(configuration, options, env) {
  const outputDirectory = join(options.outputDirectory, "evaluations", configuration.id);
  await mkdir(outputDirectory, { recursive: true });
  const args = [
    join(options.root, "runtime/adaptive-sequence.acceptance.mjs"),
    "--image", options.image,
    "--provider", configuration.provider,
    "--model", configuration.model,
    "--reasoning", configuration.reasoningEffort,
    "--repeats", String(options.repeats),
    "--fixtures", options.fixturesDirectory,
    "--commit", options.commit,
    "--no-baseline",
    "--output", outputDirectory,
  ];
  await new Promise((resolveExecution, reject) => {
    const child = spawn(process.execPath, args, { cwd: options.root, env, stdio: "inherit" });
    child.on("error", reject);
    child.on("exit", code => code === 2
      ? reject(new Error(`${configuration.id} evaluation failed before producing a report`))
      : resolveExecution(code));
  });
  return JSON.parse(await readFile(join(outputDirectory, "result.json"), "utf8"));
}

function validateConfigurationManifest(manifest) {
  if (manifest?.schemaVersion !== 1 || typeof manifest.configurationVersion !== "string"
      || !Array.isArray(manifest.configurations) || manifest.configurations.length < 2) {
    throw new Error("Invalid benchmark configuration manifest");
  }
  const ids = new Set();
  for (const configuration of manifest.configurations) {
    if (!configuration.id || ids.has(configuration.id) || !configuration.label
        || configuration.provider !== "openai" || !configuration.model || !configuration.reasoningEffort
        || typeof configuration.required !== "boolean") {
      throw new Error("Invalid benchmark configuration entry");
    }
    ids.add(configuration.id);
  }
}

function resolveCommit(root, requested) {
  const head = git(root, ["rev-parse", "HEAD"]).trim();
  if (!requested) return head;
  const pinned = git(root, ["rev-parse", requested]).trim();
  if (pinned !== head) throw new Error(`HEAD ${head} does not match pinned commit ${pinned}`);
  return pinned;
}

function git(root, args) {
  return execFileSync("git", args, { cwd: root, encoding: "utf8" });
}

function tradeoffMetrics(summary) {
  const distributions = summary.distributions ?? {};
  const costPerQuestion = distributions.costUsdPerQuestion?.p50 ?? null;
  const evidenceYield = distributions.usefulEvidencePerQuestion?.p50 ?? null;
  return {
    semanticPassRateP25: distributions.semanticPassRate?.p25 ?? null,
    semanticPassRateP50: distributions.semanticPassRate?.p50 ?? null,
    usefulEvidencePerQuestionP50: evidenceYield,
    turnsToSufficientEvidenceP50: distributions.turnsToSufficientEvidence?.p50 ?? null,
    costUsdPerQuestionP50: costPerQuestion,
    costUsdPerUsefulEvidence: typeof costPerQuestion === "number" && typeof evidenceYield === "number" && evidenceYield > 0
      ? costPerQuestion / evidenceYield
      : null,
    latencyMsP95: distributions.latencyMs?.p95 ?? null,
    semanticChecksPassedPerUsd: typeof summary.semanticPassed === "number"
      && typeof distributions.costUsdPerRun?.mean === "number" && distributions.costUsdPerRun.mean > 0
      ? summary.semanticPassed / (distributions.costUsdPerRun.mean * (distributions.costUsdPerRun.count ?? 1))
      : null,
  };
}

function meetsPromotionCriterion(summary, tradeoff) {
  const failures = summary.failureCounts ?? {};
  return summary.deterministicFailures === 0
    && summary.semanticGateFailures === 0
    && summary.continuityGateFailures === 0
    && (failures.contradictionsMissed ?? 0) === 0
    && (failures.repeatedQuestions ?? 0) === 0
    && (failures.earlyCompletions ?? 0) === 0
    && typeof tradeoff.semanticLowerQuartileDelta === "number"
    && tradeoff.semanticLowerQuartileDelta >= PROMOTION_CRITERION.minimumLowerQuartileSemanticGain
    && typeof tradeoff.evidenceYieldMedianDelta === "number"
    && tradeoff.evidenceYieldMedianDelta >= -PROMOTION_CRITERION.maximumMedianEvidenceYieldRegression
    && typeof tradeoff.costPerUsefulEvidenceRatio === "number"
    && tradeoff.costPerUsefulEvidenceRatio <= PROMOTION_CRITERION.maximumMedianCostPerUsefulEvidenceRatio
    && typeof tradeoff.p95LatencyRatio === "number"
    && tradeoff.p95LatencyRatio <= PROMOTION_CRITERION.maximumP95LatencyRatio;
}

function extractIndividualFailures(runs) {
  return runs.flatMap(run => (run.turns ?? []).flatMap(turn => {
    const location = { scenarioId: run.scenarioId, repeat: run.repeat, turn: turn.trace?.turn ?? null };
    const contract = (turn.contractViolations ?? []).map(failure => sanitise({
      ...location,
      kind: "contract",
      rule: failure.rule,
      detail: failure.message,
      sourceAnswer: failure.sourceAnswer,
      offendingAction: failure.offendingAction,
    }));
    const semantic = (turn.semanticQuality?.checks ?? []).filter(check => !check.passed).map(check => sanitise({
      ...location,
      kind: "semantic",
      rule: check.rule,
      detail: check.detail,
      sourceAnswer: turn.trace?.sourceAnswer?.text ?? turn.trace?.sourceAnswer ?? null,
      offendingAction: turn.trace?.action ?? null,
    }));
    return [...contract, ...semantic];
  }));
}

function sanitise(value) {
  if (typeof value === "string") {
    return value
      .replace(/Bearer\s+\S+/gi, "Bearer [REDACTED]")
      .replace(/\b(?:sk|sess)-[A-Za-z0-9_-]+\b/g, "[REDACTED]")
      .replace(/\bproj_[A-Za-z0-9_-]+\b/g, "[REDACTED]");
  }
  if (Array.isArray(value)) return value.map(sanitise);
  if (value && typeof value === "object") return Object.fromEntries(Object.entries(value).map(([key, entry]) => [key, sanitise(entry)]));
  return value;
}

function difference(value, baseline) {
  return typeof value === "number" && typeof baseline === "number" ? rounded(value - baseline) : null;
}

function ratio(value, baseline) {
  return typeof value === "number" && typeof baseline === "number" && baseline > 0 ? rounded(value / baseline) : null;
}

function rounded(value) {
  return Number(value.toFixed(6));
}

function minimumInteger(value, fallback, minimum) {
  if (value === undefined || value === "") {
    if (fallback !== undefined) return fallback;
    throw new Error(`Expected an integer of at least ${minimum}`);
  }
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < minimum) throw new Error(`Expected an integer of at least ${minimum}, received ${value}`);
  return parsed;
}

function timestamp() {
  return new Date().toISOString().replace(/[:.]/g, "-");
}

function yesNo(value) { return value ? "yes" : "no"; }
function percent(value) { return typeof value === "number" ? `${(value * 100).toFixed(1)}%` : "unavailable"; }
function decimal(value) { return typeof value === "number" ? value.toFixed(3) : "unavailable"; }
function usd(value) { return typeof value === "number" ? `$${value.toFixed(6)}` : "unavailable"; }
function milliseconds(value) { return typeof value === "number" ? `${value.toFixed(1)} ms` : "unavailable"; }

function benchmarkHelp() {
  return `Usage: npm run benchmark:adaptive-interview -- [options]\n\n`
    + `  --image IMAGE            Runtime image (or FINDWORKS_RUNTIME_IMAGE)\n`
    + `  --commit REV             Require HEAD to resolve to REV\n`
    + `  --repeats N              At least two trials per configuration (default: 3)\n`
    + `  --configurations FILE    Versioned configuration manifest\n`
    + `  --fixtures DIRECTORY     Versioned fixture directory\n`
    + `  --output DIRECTORY       Combined and per-configuration results\n`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  runBenchmarkCli().then(code => { process.exitCode = code; }).catch(error => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 2;
  });
}
