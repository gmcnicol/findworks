import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const skill = await readFile(new URL("./reviewer/SKILL.md", import.meta.url), "utf8");
const extension = await readFile(new URL("./reviewer-extension.ts", import.meta.url), "utf8");
const runner = await readFile(new URL("./reviewer-runner.mjs", import.meta.url), "utf8");
const interviewSkill = await readFile(new URL("./interview/SKILL.md", import.meta.url), "utf8");

test("reviewer responsibility is inspection and advisory proposals, never conversation or state mutation", () => {
  assert.match(skill, /Do not ask.*Question/i);
  assert.match(skill, /advisory/i);
  assert.match(skill, /immutable Evidence/i);
  assert.match(skill, /approved Mission/i);
  assert.match(skill, /never.*mutate|must not.*mutate/i);
  assert.match(skill, /conflict/i);
  assert.match(skill, /sufficien/i);
  assert.match(skill, /next-thread/i);
});

test("reviewer tool is separate and carries revision, Mission, Evidence, provenance, and completion safeguards", () => {
  assert.match(extension, /submit_investigation_review/);
  for (const field of [
    "requestId", "proposalId", "sessionId", "missionVersion", "expectedRevision", "evidenceRevision",
    "outcomeProposals", "nextThreadProposal", "completionAssessment", "evidenceIds",
  ]) assert.match(extension, new RegExp(field));
  assert.doesNotMatch(extension, /submit_interview_turn/);
  assert.match(runner, /--tools", "submit_investigation_review"/);
});

test("interviewer independently checks validated guidance and retains sole Question responsibility", () => {
  assert.match(interviewSkill, /reviewer_guidance/);
  assert.match(interviewSkill, /ADVISORY_ONLY/);
  assert.match(interviewSkill, /independently/i);
  assert.match(interviewSkill, /You remain responsible.*Question/i);
});
