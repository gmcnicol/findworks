import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { preparePiEnvironment } from "./auth.mjs";

test("API-key model runs receive a writable Pi configuration directory", () => {
  const root = mkdtempSync(join(tmpdir(), "findworks-pi-config-"));
  const directory = join(root, "agent");
  try {
    const environment = preparePiEnvironment({ OPENAI_API_KEY: "synthetic" }, directory);
    assert.equal(environment.PI_CODING_AGENT_DIR, directory);
    assert.equal(environment.OPENAI_API_KEY, "synthetic");
    assert.equal(existsSync(directory), true);
  } finally {
    rmSync(root, { recursive: true });
  }
});

test("an exact preflight-resolved OpenAI model is added to Pi without persisting its credential", () => {
  const root = mkdtempSync(join(tmpdir(), "findworks-pi-model-"));
  const directory = join(root, "agent");
  try {
    preparePiEnvironment({
      OPENAI_API_KEY: "synthetic",
      FINDWORKS_MODEL_PROVIDER: "openai",
      FINDWORKS_MODEL: "gpt-5.4-mini-2026-03-17",
    }, directory);

    const models = JSON.parse(readFileSync(join(directory, "models.json"), "utf8"));
    assert.equal(models.providers.openai.apiKey, undefined);
    assert.deepEqual(models.providers.openai.models, [{
      id: "gpt-5.4-mini-2026-03-17",
      name: "GPT-5.4 mini (2026-03-17)",
      api: "openai-responses",
      reasoning: true,
      input: ["text", "image"],
      cost: { input: 0.75, output: 4.5, cacheRead: 0.075, cacheWrite: 0 },
      contextWindow: 400000,
      maxTokens: 128000,
      thinkingLevelMap: { off: "none", minimal: null, low: "low", medium: "medium", high: "high", xhigh: "xhigh", max: null },
    }]);
    assert.equal(readFileSync(join(directory, "models.json"), "utf8").includes("synthetic"), false);
  } finally {
    rmSync(root, { recursive: true });
  }
});

test("OAuth model credential is file-scoped and removed from the Pi environment", () => {
  const directory = mkdtempSync(join(tmpdir(), "findworks-pi-auth-"));
  try {
    const json = JSON.stringify({ "openai-codex": { type: "oauth", access: "short-lived", refresh: "", expires: 1, accountId: "account" } });
    const environment = preparePiEnvironment({ FINDWORKS_MODEL_OAUTH_B64: Buffer.from(json).toString("base64") }, directory);
    assert.equal(environment.FINDWORKS_MODEL_OAUTH_B64, undefined);
    assert.equal(environment.PI_CODING_AGENT_DIR, directory);
    assert.equal(readFileSync(join(directory, "auth.json"), "utf8"), json);
    assert.equal(statSync(join(directory, "auth.json")).mode & 0o777, 0o600);
  } finally {
    rmSync(directory, { recursive: true });
  }
});
