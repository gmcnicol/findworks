import { execFileSync } from "node:child_process";
import { mkdir, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { createDockerRuntimeExecutor, inspectImage } from "./cli.mjs";
import { loadFixtures, runScenario, summariseRuns } from "./harness.mjs";
import { modelIdentityKey, validateModelIdentityObservations } from "./model-identity.mjs";
import { ReviewerProposalGate } from "./review-contract.mjs";
import { compareReviewStrategies, createReviewedExecutor } from "./reviewed-executor.mjs";

const moduleDirectory = dirname(fileURLToPath(import.meta.url));
const defaultRoot = resolve(moduleDirectory, "../..");

export function parsePeriodicReviewOptions(argv, env = process.env, root = defaultRoot) {
  const options = {
    image: env.FINDWORKS_RUNTIME_IMAGE,
    hostContainer: env.FINDWORKS_DOCKER_HOST_CONTAINER,
    provider: env.FINDWORKS_MODEL_PROVIDER || "openai",
    model: env.FINDWORKS_MODEL || "gpt-5.4-mini",
    reasoningEffort: env.FINDWORKS_REASONING_EFFORT || "low",
    repeats: positiveInteger(env.FINDWORKS_EVALUATION_REPEATS, 1),
    fixturesDirectory: join(root, "runtime/evaluation/fixtures/v1"),
    outputDirectory: env.FINDWORKS_EVALUATION_OUTPUT || join(root, "artifacts/periodic-review-evaluation", timestamp()),
    commit: env.FINDWORKS_EVALUATION_COMMIT,
    runtimeVersion: env.FINDWORKS_RUNTIME_VERSION,
    scenarioIds: [],
    root,
  };
  for (let index = 0; index < argv.length; index++) {
    const flag = argv[index];
    if (flag === "--help" || flag === "-h") { options.help = true; continue; }
    const value = argv[++index];
    if (value === undefined) throw new Error(`${flag} requires a value`);
    switch (flag) {
      case "--image": options.image = value; break;
      case "--commit": options.commit = value; break;
      case "--provider": options.provider = value; break;
      case "--model": options.model = value; break;
      case "--reasoning": options.reasoningEffort = value; break;
      case "--repeats": options.repeats = positiveInteger(value); break;
      case "--fixtures": options.fixturesDirectory = resolve(value); break;
      case "--output": options.outputDirectory = resolve(value); break;
      case "--runtime-version": options.runtimeVersion = value; break;
      case "--scenario": options.scenarioIds.push(value); break;
      default: throw new Error(`Unknown option: ${flag}`);
    }
  }
  return options;
}

export async function runPeriodicReviewCli(argv = process.argv.slice(2), env = process.env) {
  const options = parsePeriodicReviewOptions(argv, env);
  if (options.help) {
    process.stdout.write(help());
    return 0;
  }
  if (!options.image) throw new Error("Set FINDWORKS_RUNTIME_IMAGE or pass --image");
  const commit = resolveCommit(options.root, options.commit);
  const image = inspectImage(options.image, options.hostContainer);
  const runtimeCommit = image.labels["org.opencontainers.image.revision"];
  if (runtimeCommit && runtimeCommit !== "unknown" && runtimeCommit !== commit) {
    throw new Error(`Runtime image commit ${runtimeCommit} does not match evaluated commit ${commit}`);
  }
  const allFixtures = await loadFixtures(options.fixturesDirectory);
  const fixtures = options.scenarioIds.length === 0
    ? allFixtures
    : allFixtures.filter(fixture => options.scenarioIds.includes(fixture.id));
  const missing = options.scenarioIds.filter(id => !fixtures.some(fixture => fixture.id === id));
  if (missing.length > 0) throw new Error(`Unknown scenario(s): ${missing.join(", ")}`);

  const baselineExecutor = await createDockerRuntimeExecutor(options, env);
  const reviewedInterviewer = await createDockerRuntimeExecutor(options, env);
  const reviewer = await createDockerRuntimeExecutor(options, env, {
    role: "reviewer",
    endpoint: "/review",
    requestIdentity: value => value.requestId,
    responseIdentity: value => value.requestId,
  });
  const baselineRuns = [];
  const reviewedRuns = [];
  const startedAt = new Date().toISOString();
  try {
    for (let repeat = 1; repeat <= options.repeats; repeat++) {
      for (const fixture of fixtures) {
        baselineRuns.push(await runScenario({ fixture, repeat, executeRuntime: baselineExecutor.execute }));
        const reviewedExecute = createReviewedExecutor({
          gate: new ReviewerProposalGate(),
          executeReviewer: reviewer.execute,
          executeInterviewer: reviewedInterviewer.execute,
        });
        reviewedRuns.push(await runScenario({ fixture, repeat, executeRuntime: reviewedExecute }));
      }
    }
  } finally {
    await Promise.all([baselineExecutor.close(), reviewedInterviewer.close(), reviewer.close()]);
  }

  const sharedMetadata = {
    provider: options.provider,
    model: `${options.provider}/${options.model}`,
    requestedModel: options.model,
    reasoningEffort: options.reasoningEffort,
    runtimeVersion: options.runtimeVersion ?? image.labels["io.findworks.pi.version"] ?? null,
    runtimeImage: options.image,
    runtimeImageId: image.id,
    runtimeCommit: image.labels["org.opencontainers.image.revision"] ?? null,
    fixtureSchemaVersion: 1,
    fixtureVersions: Object.fromEntries(fixtures.map(fixture => [fixture.id, fixture.fixtureVersion])),
    repeats: options.repeats,
  };
  const report = buildPeriodicReviewReport({
    metadata: {
      schemaVersion: 1,
      startedAt,
      completedAt: new Date().toISOString(),
      commit,
      workingTreeDirty: git(options.root, ["status", "--porcelain"]).trim() !== "",
      ...sharedMetadata,
    },
    baseline: strategyReport(sharedMetadata, baselineRuns),
    reviewed: strategyReport(sharedMetadata, reviewedRuns),
  });
  await writePeriodicReviewReport(report, options.outputDirectory);
  process.stdout.write(formatPeriodicReviewSummary(report));
  process.stdout.write(`JSON: ${join(options.outputDirectory, "result.json")}\nSummary: ${join(options.outputDirectory, "summary.md")}\n`);
  return report.reviewed.summary.deterministicFailures > 0
    || report.reviewed.summary.semanticGateFailures > 0
    || report.reviewed.summary.continuityGateFailures > 0
    || report.reviewed.summary.reviewer.rejected > 0 ? 1 : 0;
}

export function buildPeriodicReviewReport({ metadata, baseline, reviewed }) {
  if (baseline.metadata?.model !== reviewed.metadata?.model
      || baseline.metadata?.reasoningEffort !== reviewed.metadata?.reasoningEffort) {
    throw new Error("Baseline and reviewed strategies must use the same model and reasoning effort");
  }
  const actualModelIdentities = validatePeriodicReviewModelIdentities({ metadata, baseline, reviewed });
  return {
    schemaVersion: 1,
    metadata: { ...metadata, actualModelIdentities },
    contractVersion: "periodic-investigation-review/1.0.0",
    cadencePolicy: {
      initialEvidence: true,
      uncertaintySourceTypes: ["DONT_KNOW", "OWNER_GAP", "DECLINED"],
      threadBoundary: true,
      maximumAcceptedAnswersBetweenReviews: 4,
      perTurnReview: false,
    },
    decisionCriterion: {
      reliabilityGainRequired: true,
      noSafetyRegression: true,
      noUsefulEvidencePerQuestionRegression: true,
      maximumMedianCostPerQuestionRatio: 2,
      maximumP95LatencyRatio: 2,
    },
    baseline: {
      ...baseline,
      metadata: {
        ...baseline.metadata,
        actualModelIdentities: { interviewer: actualModelIdentities.baselineInterviewer },
      },
    },
    reviewed: {
      ...reviewed,
      metadata: {
        ...reviewed.metadata,
        actualModelIdentities: {
          interviewer: actualModelIdentities.reviewedInterviewer,
          reviewer: actualModelIdentities.reviewer,
        },
      },
    },
    comparison: compareReviewStrategies({ baseline: baseline.summary, reviewed: reviewed.summary }),
  };
}

export function formatPeriodicReviewSummary(report) {
  const baseline = report.baseline.summary;
  const reviewed = report.reviewed.summary;
  const comparison = report.comparison;
  const lines = [
    "# Periodic investigation review spike",
    "",
    `- Decision: **${comparison.decision}** — ${comparison.rationale}`,
    `- Commit: \`${report.metadata.commit}\``,
    `- Requested model/reasoning: \`${report.baseline.metadata.model}\` / \`${report.baseline.metadata.reasoningEffort}\``,
    `- Observed model identities: baseline interviewer \`${identityText(report.metadata.actualModelIdentities?.baselineInterviewer)}\`; reviewed interviewer \`${identityText(report.metadata.actualModelIdentities?.reviewedInterviewer)}\`; reviewer \`${identityText(report.metadata.actualModelIdentities?.reviewer)}\``,
    `- Runtime: Pi \`${report.metadata.runtimeVersion ?? "unavailable"}\`, image \`${report.metadata.runtimeImageId ?? "unavailable"}\``,
    `- Fixture versions: ${Object.entries(report.metadata.fixtureVersions ?? {}).map(([id, version]) => `${id}@${version}`).join(", ")}`,
    `- Repeats: ${report.metadata.repeats}`,
    "",
    "| Measure | Single interviewer | Interviewer + periodic reviewer | Change |",
    "| --- | ---: | ---: | ---: |",
    `| Useful Evidence / Question | ${decimal(baseline.usefulEvidencePerQuestion)} | ${decimal(reviewed.usefulEvidencePerQuestion)} | ${signed(comparison.usefulEvidencePerQuestionDelta)} |`,
    `| Missed conflicts | ${baseline.failureCounts?.contradictionsMissed ?? 0} | ${reviewed.failureCounts?.contradictionsMissed ?? 0} | ${signed(comparison.missedConflictDelta)} |`,
    `| Completion errors | ${baseline.failureCounts?.completionErrors ?? 0} | ${reviewed.failureCounts?.completionErrors ?? 0} | ${signed(comparison.completionErrorDelta)} |`,
    `| Deterministic failures | ${baseline.deterministicFailures} | ${reviewed.deterministicFailures} | ${signed(reviewed.deterministicFailures - baseline.deterministicFailures)} |`,
    `| Required semantic-gate failures | ${baseline.semanticGateFailures} | ${reviewed.semanticGateFailures} | ${signed(reviewed.semanticGateFailures - baseline.semanticGateFailures)} |`,
    `| Continuity-gate failures | ${baseline.continuityGateFailures} | ${reviewed.continuityGateFailures} | ${signed(reviewed.continuityGateFailures - baseline.continuityGateFailures)} |`,
    `| Median cost / Question | ${usd(baseline.distributions?.costUsdPerQuestion?.p50)} | ${usd(reviewed.distributions?.costUsdPerQuestion?.p50)} | ${ratioText(comparison.costPerQuestionRatio)} |`,
    `| p95 end-to-end turn latency | ${milliseconds(baseline.latencyMs?.p95)} | ${milliseconds(reviewed.latencyMs?.p95)} | ${ratioText(comparison.p95LatencyRatio)} |`,
    `| Reviewer cadence | 0/${baseline.turns ?? 0} | ${comparison.reviewerCadence.invocations}/${comparison.reviewerCadence.eligibleTurns} | ${percent(comparison.reviewerCadence.rate)} |`,
    "",
    "## Reviewer cadence",
    "",
    `Accepted proposals: ${reviewed.reviewer?.accepted ?? 0}; rejected proposals: ${reviewed.reviewer?.rejected ?? 0}. Triggers: ${JSON.stringify(reviewed.reviewer?.triggers ?? {})}. Reviewer latency p50/p95: ${milliseconds(reviewed.reviewer?.latencyMs?.p50)} / ${milliseconds(reviewed.reviewer?.latencyMs?.p95)}.`,
    "",
    "## Safeguard decision",
    "",
    `- No safety regression: ${yesNo(comparison.safeguards.noSafetyRegression)}`,
    `- Reliability gain: ${yesNo(comparison.safeguards.reliabilityGain)}`,
    `- Useful Evidence protected: ${yesNo(comparison.safeguards.evidenceProtected)}`,
    `- Cost and latency bounded: ${yesNo(comparison.safeguards.operationallyBounded)}`,
  ];
  return `${lines.join("\n")}\n`;
}

export function validatePeriodicReviewModelIdentities({ metadata, baseline, reviewed }) {
  const expected = {
    provider: metadata.provider,
    requestedModel: metadata.requestedModel,
    reasoningEffort: metadata.reasoningEffort,
  };
  const baselineTurns = successfulTurns(baseline.runs);
  const reviewedTurns = successfulTurns(reviewed.runs);
  const reviewerTurns = reviewedTurns.filter(turn => turn.trace?.review?.executionSucceeded);
  const observed = {
    baselineInterviewer: {
      successfulTurns: baselineTurns.length,
      identity: validateModelIdentityObservations(
        baselineTurns.map(turn => turn.usage?.modelIdentity),
        expected,
        "Baseline interviewer Runtime Turn",
      ),
    },
    reviewedInterviewer: {
      successfulTurns: reviewedTurns.length,
      identity: validateModelIdentityObservations(
        reviewedTurns.map(turn => turn.interviewerUsage?.modelIdentity),
        expected,
        "Reviewed interviewer Runtime Turn",
      ),
    },
    reviewer: {
      successfulTurns: reviewerTurns.length,
      identity: validateModelIdentityObservations(
        reviewerTurns.map(turn => turn.reviewerUsage?.modelIdentity),
        expected,
        "Reviewer execution",
      ),
    },
  };
  const identities = Object.values(observed).map(value => modelIdentityKey(value.identity));
  if (new Set(identities).size !== 1) {
    throw new Error("Baseline interviewer, reviewed interviewer, and reviewer must use the same observed model identity");
  }
  return observed;
}

export async function writePeriodicReviewReport(report, outputDirectory) {
  await mkdir(outputDirectory, { recursive: true });
  await writeFile(join(outputDirectory, "result.json"), `${JSON.stringify(report, null, 2)}\n`);
  await writeFile(join(outputDirectory, "summary.md"), formatPeriodicReviewSummary(report));
}

function successfulTurns(runs = []) {
  return runs.flatMap(run => run.turns ?? []).filter(turn => !turn.executionError);
}

function identityText(observed) {
  const identity = observed?.identity;
  if (!identity) return "unavailable";
  return `${identity.provider}/${identity.requestedModel} -> ${identity.responseModel} (${identity.reasoningEffort}; ${observed.successfulTurns} turns)`;
}

function strategyReport(metadata, runs) {
  return {
    metadata: {
      ...metadata,
      tokenUsage: aggregateUsage(runs),
      costUsd: sum(runs.map(run => run.usage?.costUsd)),
    },
    summary: summariseRuns(runs),
    runs,
  };
}

function aggregateUsage(runs) {
  const result = { available: false };
  for (const field of ["inputTokens", "outputTokens", "cacheWriteTokens"]) {
    const value = sum(runs.map(run => run.usage?.[field]));
    result[field] = value;
    if (value !== null) result.available = true;
  }
  return result;
}

function sum(values) {
  const available = values.filter(value => typeof value === "number");
  return available.length === 0 ? null : available.reduce((total, value) => total + value, 0);
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

function positiveInteger(value, fallback) {
  if (value === undefined || value === "") {
    if (fallback !== undefined) return fallback;
    throw new Error("Expected a positive integer");
  }
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) throw new Error(`Expected a positive integer, received ${value}`);
  return parsed;
}

function timestamp() { return new Date().toISOString().replace(/[:.]/g, "-"); }
function decimal(value) { return typeof value === "number" ? value.toFixed(3) : "unavailable"; }
function percent(value) { return typeof value === "number" ? `${(value * 100).toFixed(1)}%` : "unavailable"; }
function usd(value) { return typeof value === "number" ? `$${value.toFixed(6)}` : "unavailable"; }
function milliseconds(value) { return typeof value === "number" ? `${value.toFixed(1)} ms` : "unavailable"; }
function ratioText(value) { return typeof value === "number" ? `${value.toFixed(2)}×` : "unavailable"; }
function signed(value) { return typeof value !== "number" ? "unavailable" : value > 0 ? `+${value}` : String(value); }
function yesNo(value) { return value ? "yes" : "no"; }

function help() {
  return `Usage: npm run evaluate:periodic-review -- [options]\n\n`
    + `  --image IMAGE          Runtime image (or FINDWORKS_RUNTIME_IMAGE)\n`
    + `  --commit REV           Require HEAD to resolve to REV\n`
    + `  --provider NAME        Model provider (default: openai)\n`
    + `  --model NAME           Shared interviewer/reviewer model\n`
    + `  --reasoning LEVEL      Shared reasoning effort\n`
    + `  --repeats N            Repeat every shared fixture\n`
    + `  --scenario ID          Run one scenario; repeat to select several\n`
    + `  --fixtures DIRECTORY   Shared versioned fixture directory\n`
    + `  --output DIRECTORY     Paired result directory\n`
    + `  --runtime-version VER  Record an explicit runtime version\n`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  runPeriodicReviewCli().then(code => { process.exitCode = code; }).catch(error => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 2;
  });
}
