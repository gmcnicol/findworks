import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const workflowUrl = new URL("../../.github/workflows/adaptive-interview-evaluation.yml", import.meta.url);

test("workflow dispatch inputs never enter shell source while model credentials are present", async () => {
  const workflow = await readFile(workflowUrl, "utf8");
  const runBlocks = [...workflow.matchAll(/run: \|\n((?: {10}.*\n?)*)/g)].map(match => match[1]);

  assert.ok(runBlocks.length > 0);
  for (const block of runBlocks) assert.doesNotMatch(block, /\$\{\{\s*inputs\./);
  for (const name of ["model", "reasoning", "repeats", "scenario"]) {
    assert.match(workflow, new RegExp(`EVALUATION_${name.toUpperCase()}: \\$\\{\\{ inputs\\.${name} \\}\\}`));
  }
});
