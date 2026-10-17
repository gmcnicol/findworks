import { spawn, execFileSync } from "node:child_process";
import { createServer } from "node:http";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import {
  compareWithBaseline,
  formatSummary,
  loadFixtures,
  runScenario,
  summariseRuns,
} from "./harness.mjs";
import { validateModelIdentityObservations } from "./model-identity.mjs";
import { createRuntimeTurnContract } from "./runtime-turn-contract.mjs";

const moduleDirectory = dirname(fileURLToPath(import.meta.url));
const defaultRoot = resolve(moduleDirectory, "../..");

export function parseOptions(argv, env = process.env, root = defaultRoot) {
  const options = {
    image: env.FINDWORKS_RUNTIME_IMAGE,
    hostContainer: env.FINDWORKS_DOCKER_HOST_CONTAINER,
    provider: env.FINDWORKS_MODEL_PROVIDER || "openai",
    model: env.FINDWORKS_MODEL || "gpt-5.4-mini",
    reasoningEffort: env.FINDWORKS_REASONING_EFFORT || "low",
    resolvedModel: env.FINDWORKS_RESOLVED_MODEL,
    repeats: positiveInteger(env.FINDWORKS_EVALUATION_REPEATS, 1),
    fixturesDirectory: join(root, "runtime/evaluation/fixtures/v1"),
    baselinePath: join(root, "runtime/evaluation/baselines/gpt-5.4-mini-low.json"),
    baselineExplicit: false,
    outputDirectory: env.FINDWORKS_EVALUATION_OUTPUT || join(root, "artifacts/adaptive-interview-evaluation", timestamp()),
    commit: env.FINDWORKS_EVALUATION_COMMIT,
    runtimeVersion: env.FINDWORKS_RUNTIME_VERSION,
    scenarioIds: [],
    root,
  };
  for (let index = 0; index < argv.length; index++) {
    const flag = argv[index];
    if (flag === "--no-baseline") { options.baselinePath = null; options.baselineExplicit = true; continue; }
    if (flag === "--help" || flag === "-h") { options.help = true; continue; }
    const value = argv[++index];
    if (value === undefined) throw new Error(`${flag} requires a value`);
    switch (flag) {
      case "--image": options.image = value; break;
      case "--provider": options.provider = value; break;
      case "--model": options.model = value; break;
      case "--reasoning": options.reasoningEffort = value; break;
      case "--resolved-model": options.resolvedModel = value; break;
      case "--repeats": options.repeats = positiveInteger(value); break;
      case "--fixtures": options.fixturesDirectory = resolve(value); break;
      case "--baseline": options.baselinePath = resolve(value); options.baselineExplicit = true; break;
      case "--output": options.outputDirectory = resolve(value); break;
      case "--commit": options.commit = value; break;
      case "--runtime-version": options.runtimeVersion = value; break;
      case "--scenario": options.scenarioIds.push(value); break;
      default: throw new Error(`Unknown option: ${flag}`);
    }
  }
  return options;
}

export async function runCli(argv = process.argv.slice(2), env = process.env) {
  const options = parseOptions(argv, env);
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
  let baseline = await readBaseline(options.baselinePath);
  const credential = selectedProviderCredential(options.provider, env);
  const runtime = await createDockerRuntimeExecutor(options, env);
  const runs = [];
  const startedAt = new Date().toISOString();
  try {
    for (let repeat = 1; repeat <= options.repeats; repeat++) {
      for (const fixture of fixtures) {
        runs.push(await runScenario({ fixture, repeat, executeRuntime: runtime.execute }));
      }
    }
  } finally {
    await runtime.close();
  }

  const modelIdentity = validateRunModelIdentity(runs, {
    provider: options.provider,
    requestedModel: options.model,
    responseModel: options.resolvedModel,
    reasoningEffort: options.reasoningEffort,
  });
  const compatibilityMetadata = {
    commit,
    runtimeCommit: image.labels["org.opencontainers.image.revision"] ?? null,
    provider: options.provider,
    model: `${options.provider}/${options.model}`,
    resolvedModel: modelIdentity.responseModel,
    reasoningEffort: modelIdentity.reasoningEffort,
    credentialKind: credential.kind,
    credentialEnvironment: credential.name,
    runtimeVersion: options.runtimeVersion ?? image.labels["io.findworks.pi.version"] ?? null,
    fixtureSchemaVersion: 1,
    fixtureVersions: Object.fromEntries(fixtures.map(fixture => [fixture.id, fixture.fixtureVersion])),
  };
  if (baseline) {
    try {
      validateBaselineCompatibility(baseline, compatibilityMetadata);
    } catch (error) {
      if (options.baselineExplicit) throw error;
      baseline = null;
    }
  }
  const summary = summariseRuns(runs);
  const comparison = compareWithBaseline(summary, baseline?.summary ?? baseline);
  const report = {
    schemaVersion: 1,
    metadata: {
      startedAt,
      completedAt: new Date().toISOString(),
      commit,
      workingTreeDirty: git(options.root, ["status", "--porcelain"]).trim() !== "",
      provider: options.provider,
      model: `${options.provider}/${options.model}`,
      resolvedModel: modelIdentity.responseModel,
      reasoningEffort: modelIdentity.reasoningEffort,
      credentialKind: credential.kind,
      credentialEnvironment: credential.name,
      runtimeVersion: options.runtimeVersion ?? image.labels["io.findworks.pi.version"] ?? null,
      runtimeImage: options.image,
      runtimeImageId: image.id,
      runtimeCommit: image.labels["org.opencontainers.image.revision"] ?? null,
      fixtureSchemaVersion: 1,
      fixtureVersions: Object.fromEntries(fixtures.map(fixture => [fixture.id, fixture.fixtureVersion])),
      repeats: options.repeats,
      tokenUsage: aggregateRunMetric(runs, "inputTokens", "outputTokens", "cacheWriteTokens"),
      costUsd: aggregateRunMetric(runs, "costUsd"),
    },
    baseline: baseline ? { path: options.baselinePath, metadata: baseline.metadata ?? null } : null,
    summary,
    comparison,
    runs,
  };
  const paths = await writeReport(report, options.outputDirectory);
  process.stdout.write(formatSummary(report));
  process.stdout.write(`JSON: ${paths.json}\nSummary: ${paths.markdown}\n`);
  return summary.deterministicFailures > 0 || summary.semanticGateFailures > 0
    || summary.continuityGateFailures > 0 || comparison?.regressed ? 1 : 0;
}

export async function writeReport(report, outputDirectory) {
  await mkdir(outputDirectory, { recursive: true });
  const json = join(outputDirectory, "result.json");
  const markdown = join(outputDirectory, "summary.md");
  await writeFile(json, `${JSON.stringify(report, null, 2)}\n`);
  await writeFile(markdown, formatSummary(report));
  return { json, markdown };
}

export async function createDockerRuntimeExecutor(options, env, protocol = {}) {
  const role = protocol.role ?? "interviewer";
  const endpoint = protocol.endpoint ?? (role === "reviewer" ? "/review" : "/turn");
  const requestIdentity = protocol.requestIdentity ?? (value => value.run_id);
  const responseIdentity = protocol.responseIdentity ?? (value => role === "reviewer" ? value.requestId : value.runId);
  let pending;
  const server = createServer(async (request, response) => {
    if (request.method !== "POST" || request.url !== endpoint) {
      response.writeHead(404).end();
      return;
    }
    if (request.headers.authorization !== "Bearer acceptance-token") {
      response.writeHead(401).end();
      return;
    }
    let body = "";
    for await (const chunk of request) body += chunk;
    let submission;
    try {
      submission = JSON.parse(body);
    } catch {
      response.writeHead(400, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ code: "invalid_json" }));
      pending?.reject(new Error("Runtime submitted invalid JSON"));
      pending = undefined;
      return;
    }
    if (!pending || responseIdentity(submission) !== pending.identity) {
      response.writeHead(409, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ code: "stale_revision" }));
      return;
    }
    if (role !== "reviewer") {
      const result = pending.contract.submit(submission);
      if (!result.accepted) {
        pending.rejections.push({ code: result.code, submission });
        response.writeHead(400, { "Content-Type": "application/json" });
        response.end(JSON.stringify({ code: result.code }));
        return;
      }
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(JSON.stringify({ event: result.event }));
      pending.resolve({
        submission,
        event: result.event,
        accepted: true,
        authoritativeContext: result.context,
        rejectedSubmissions: pending.rejections,
      });
      pending = undefined;
      return;
    }
    const event = "review_proposed";
    response.writeHead(200, { "Content-Type": "application/json" });
    response.end(JSON.stringify({ event }));
    pending.resolve({ submission, event, accepted: true, rejectedSubmissions: [] });
    pending = undefined;
  });
  await new Promise((resolveListen, reject) => {
    server.once("error", reject);
    server.listen(0, "0.0.0.0", resolveListen);
  });
  const port = server.address().port;

  return {
    execute: context => new Promise((resolveExecution, rejectExecution) => {
      if (pending) return rejectExecution(new Error("Runtime executor only supports serial turns"));
      const started = process.hrtime.bigint();
      let child;
      const timeout = setTimeout(() => {
        pending = undefined;
        child?.kill("SIGTERM");
        rejectExecution(new Error("Runtime did not submit a turn within 120 seconds"));
      }, 120_000);
      let received;
      let runtimeOutput = "";
      pending = {
        identity: requestIdentity(context),
        contract: role === "reviewer" ? null : createRuntimeTurnContract(context),
        rejections: [],
        resolve(value) { received = value; },
        reject(error) { clearTimeout(timeout); rejectExecution(error); },
      };
      child = spawnRuntime(options, env, context, port, { role, endpoint });
      child.stdout.on("data", chunk => {
        if (runtimeOutput.length < 4_096) runtimeOutput += chunk.toString("utf8").slice(0, 4_096 - runtimeOutput.length);
      });
      child.on("error", error => {
        pending = undefined;
        clearTimeout(timeout);
        rejectExecution(error);
      });
      child.on("exit", code => {
        pending = undefined;
        clearTimeout(timeout);
        if (code !== 0) {
          rejectExecution(new Error(`Runtime exited with ${code}`));
          return;
        }
        if (!received) {
          rejectExecution(new Error("Runtime exited without a turn submission"));
          return;
        }
        resolveExecution({
          ...received,
          durationMs: Number(process.hrtime.bigint() - started) / 1_000_000,
          usage: parseRuntimeMetrics(runtimeOutput),
        });
      });
    }),
    close: () => new Promise((resolveClose, reject) => server.close(error => error ? reject(error) : resolveClose())),
  };
}

function spawnRuntime(options, env, context, port, { role, endpoint }) {
  const dockerArgs = [
    "run", "--rm", "-i", "--read-only", "--add-host", "host.docker.internal:host-gateway",
    "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m", "--cap-drop", "ALL",
    "--security-opt", "no-new-privileges", "--pids-limit", "64", "--memory", "512m", "--cpus", "0.5",
    "--user", "10001:10001",
  ];
  const credential = selectedProviderCredential(options.provider, env);
  dockerArgs.push("--env", credential.name);
  dockerArgs.push(
    "--env", `FINDWORKS_MODEL_PROVIDER=${options.provider}`,
    "--env", `FINDWORKS_MODEL=${options.model}`,
    "--env", `FINDWORKS_REASONING_EFFORT=${options.reasoningEffort}`,
    "--env", `FINDWORKS_RUNTIME_ROLE=${role}`,
    "--env", "NO_PROXY=host.docker.internal",
  );
  if (role === "reviewer") {
    dockerArgs.push(
      "--env", `FINDWORKS_REVIEW_URL=http://host.docker.internal:${port}${endpoint}`,
      "--env", "FINDWORKS_REVIEW_TOKEN=acceptance-token",
    );
  } else {
    dockerArgs.push(
      "--env", `FINDWORKS_TURN_URL=http://host.docker.internal:${port}${endpoint}`,
      "--env", "FINDWORKS_TURN_TOKEN=acceptance-token",
    );
  }
  dockerArgs.push(options.image);
  const args = options.hostContainer
    ? ["exec", "-i", options.hostContainer, "docker", ...dockerArgs]
    : dockerArgs;
  const child = spawn("docker", args, { stdio: ["pipe", "pipe", "ignore"], env });
  child.stdin.end(`${JSON.stringify(context)}\n`);
  return child;
}

export function validateBaselineCompatibility(baseline, current) {
  if (baseline?.schemaVersion !== 1 || !baseline.metadata || !baseline.summary) {
    throw new Error("Baseline schema is not a supported evaluation result");
  }
  if (typeof baseline.metadata.commit !== "string" || baseline.metadata.runtimeCommit !== baseline.metadata.commit) {
    throw new Error("Baseline commit and runtime image identity are missing or inconsistent");
  }
  if (typeof current.commit !== "string" || current.runtimeCommit !== current.commit) {
    throw new Error("Current commit and runtime image identity are missing or inconsistent with the baseline contract");
  }
  const fields = [
    "provider", "model", "resolvedModel", "reasoningEffort",
    "credentialKind", "credentialEnvironment", "runtimeVersion", "fixtureSchemaVersion",
  ];
  for (const field of fields) {
    if (baseline.metadata[field] == null || baseline.metadata[field] !== current[field]) {
      throw new Error(`Baseline ${field} is incompatible with this evaluation run`);
    }
  }
  if (JSON.stringify(sortedObject(baseline.metadata.fixtureVersions))
      !== JSON.stringify(sortedObject(current.fixtureVersions))) {
    throw new Error("Baseline fixture IDs or versions are incompatible with this evaluation run");
  }
}

export function validateRunModelIdentity(runs, expected) {
  const successfulTurns = runs.flatMap(run => run.turns ?? [])
    .filter(turn => !turn.executionError);
  return validateModelIdentityObservations(
    successfulTurns.map(turn => turn.usage?.modelIdentity),
    expected,
    "actual Runtime Turn",
    "preflight",
  );
}

export function selectedProviderCredential(provider, env) {
  const credentialByProvider = {
    openai: { name: "OPENAI_API_KEY", kind: "api-key" },
    "openai-codex": { name: "FINDWORKS_MODEL_OAUTH_B64", kind: "oauth" },
    anthropic: { name: "ANTHROPIC_API_KEY", kind: "api-key" },
    google: { name: "GEMINI_API_KEY", kind: "api-key" },
    gemini: { name: "GEMINI_API_KEY", kind: "api-key" },
  };
  const credential = credentialByProvider[provider];
  if (!credential) throw new Error(`Unsupported provider credential mapping: ${provider}`);
  if (provider === "openai" && env.FINDWORKS_MODEL_OAUTH_B64) {
    if (env.OPENAI_API_KEY) throw new Error("Multiple credentials are configured for openai; select one explicit provider credential path");
    throw new Error("OpenAI OAuth cannot substitute for the openai API-key provider; select openai-codex explicitly");
  }
  if (!env[credential.name]) throw new Error(`${credential.name} is required for provider ${provider}`);
  return credential;
}

export function parseRuntimeMetrics(output) {
  for (const line of String(output).split("\n").reverse()) {
    if (!line.trim()) continue;
    try {
      const metrics = JSON.parse(line).findworksRuntimeMetrics;
      if (!metrics || typeof metrics !== "object") continue;
      const value = {};
      for (const field of ["inputTokens", "outputTokens", "cacheWriteTokens", "costUsd"]) {
        if (metrics[field] !== undefined) {
          if (typeof metrics[field] !== "number" || !Number.isFinite(metrics[field]) || metrics[field] < 0) return null;
          value[field] = metrics[field];
        }
      }
      if (metrics.modelIdentity !== undefined) {
        const identity = metrics.modelIdentity;
        if (!identity || typeof identity !== "object"
            || ["provider", "requestedModel", "responseModel", "reasoningEffort"]
              .some(field => typeof identity[field] !== "string" || identity[field] === "")) return null;
        value.modelIdentity = {
          provider: identity.provider,
          requestedModel: identity.requestedModel,
          responseModel: identity.responseModel,
          reasoningEffort: identity.reasoningEffort,
        };
      }
      return Object.keys(value).length > 0 ? value : null;
    } catch {
      continue;
    }
  }
  return null;
}

function resolveCommit(root, requested) {
  const head = git(root, ["rev-parse", "HEAD"]).trim();
  if (!requested) return head;
  const pinned = git(root, ["rev-parse", requested]).trim();
  if (pinned !== head) throw new Error(`HEAD ${head} does not match pinned commit ${pinned}`);
  return pinned;
}

export function inspectImage(image, hostContainer) {
  try {
    const args = hostContainer
      ? ["exec", hostContainer, "docker", "image", "inspect", image]
      : ["image", "inspect", image];
    const inspected = JSON.parse(execFileSync("docker", args, { encoding: "utf8" }))[0];
    return { id: inspected.Id ?? null, labels: inspected.Config?.Labels ?? {} };
  } catch {
    return { id: null, labels: {} };
  }
}

async function readBaseline(path) {
  if (!path) return null;
  return JSON.parse(await readFile(path, "utf8"));
}

function git(root, args) {
  return execFileSync("git", args, { cwd: root, encoding: "utf8" });
}

function aggregateRunMetric(runs, ...fields) {
  const result = {};
  let available = false;
  for (const field of fields) {
    const values = runs.map(run => run.usage?.[field]).filter(value => typeof value === "number");
    result[field] = values.length === 0 ? null : values.reduce((total, value) => total + value, 0);
    if (values.length > 0) available = true;
  }
  return fields.length === 1 ? result[fields[0]] : { ...result, available };
}

function sortedObject(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  return Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right)));
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

function timestamp() {
  return new Date().toISOString().replace(/[:.]/g, "-");
}

function help() {
  return `Usage: npm run test:adaptive-interview -- [options]\n\n` +
    `  --image IMAGE          Runtime image (or FINDWORKS_RUNTIME_IMAGE)\n` +
    `  --commit REV           Require HEAD to resolve to REV\n` +
    `  --provider NAME        Model provider (default: openai)\n` +
    `  --model NAME           Model name (default: gpt-5.4-mini)\n` +
    `  --reasoning LEVEL      Pi reasoning effort (default: low)\n` +
    `  --resolved-model ID    Require actual provider response model from preflight\n` +
    `  --repeats N            Repeat every scenario N times\n` +
    `  --scenario ID          Run one scenario; repeat to select several\n` +
    `  --fixtures DIRECTORY   Versioned fixture directory\n` +
    `  --baseline FILE        Baseline result or summary\n` +
    `  --no-baseline          Do not compare a baseline\n` +
    `  --output DIRECTORY     Result directory\n` +
    `  --runtime-version VER  Record an explicit runtime version\n`;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  runCli().then(code => { process.exitCode = code; }).catch(error => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 2;
  });
}
