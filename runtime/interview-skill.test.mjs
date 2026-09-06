import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const skill = readFileSync(new URL("./interview/SKILL.md", import.meta.url), "utf8");
const runner = readFileSync(new URL("./runner.mjs", import.meta.url), "utf8");
const extension = readFileSync(new URL("./extension.ts", import.meta.url), "utf8");

test("interview skill adapts design-tree grilling without leading the interviewee", () => {
  assert.match(skill, /Work the Mission as a design tree/);
  assert.match(skill, /The \*\*frontier\*\* is the set of unresolved branches/);
  assert.match(skill, /Recompute it after every answer/);
  assert.match(skill, /highest value for reducing material uncertainty/);
  assert.match(skill, /Ask one focused plain-language question at a time/);
  assert.match(skill, /Never suggest the desired answer/);
});

test("interview skill tests real sufficiency instead of completing from mention or silence", () => {
  assert.match(skill, /sufficient-evidence descriptions are the branch's exit conditions/);
  assert.match(skill, /A mention is not sufficient exploration/);
  assert.match(skill, /Silence, omission, and the absence of extra detail prove nothing/);
  assert.match(skill, /One rich answer may support several results only when it explicitly satisfies each affected branch/);
  assert.match(skill, /required frontier is empty/);
});

test("interview outcomes are deltas against durable current outcomes", () => {
  assert.match(skill, /current_outcomes/);
  assert.match(skill, /outcomes array is a delta/);
  assert.match(skill, /Do not resubmit an unchanged claim or a paraphrase of it/);
  assert.match(runner, /outcomes as a delta against current_outcomes/);
  assert.match(extension, /Only new or materially changed outcomes/);
});

test("runner explicitly expands and activates bounded design-tree grilling", () => {
  assert.match(runner, /dependency-aware design-tree grilling method/);
  assert.match(runner, /ask one respectful non-leading question at a time/);
  assert.match(runner, /\/skill:findworks-interview/);
});

test("question actions require the server-scoped result identity", () => {
  assert.match(extension, /nextAction: Type\.Union\(\[/);
  assert.match(extension, /Type\.Literal\("ASK_QUESTION"\)[\s\S]*resultId: Type\.String\(\)/);
});
