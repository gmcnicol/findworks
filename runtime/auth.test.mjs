import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { preparePiEnvironment } from "./auth.mjs";

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
