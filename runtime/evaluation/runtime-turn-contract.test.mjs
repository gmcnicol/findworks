import assert from "node:assert/strict";
import test from "node:test";
import { createRuntimeTurnContract } from "./runtime-turn-contract.mjs";

const resultId = "10000000-0000-0000-0000-000000000001";
const otherResultId = "10000000-0000-0000-0000-000000000002";
const evidenceId = "40000000-0000-0000-0000-000000000001";

function context() {
  return {
    run_id: "20000000-0000-0000-0000-000000000001",
    expected_revision: 2,
    investigation_results: [{
      result_id: resultId,
      required: true,
      coverage: "EXPLORING",
      current_outcomes: [],
    }],
    questions_and_evidence: [{
      evidence_id: evidenceId,
      result_id: resultId,
      exact_text: "The analyst checks the source ledger.",
    }],
    conversation_state: { current_thread_result_id: resultId },
  };
}

function validSubmission() {
  return {
    runId: context().run_id,
    expectedRevision: 2,
    outcomes: [],
    nextAction: {
      type: "ASK_QUESTION",
      resultId,
      text: "What does the analyst check next?",
      responseMode: "FREE_TEXT",
    },
  };
}

test("production Runtime Turn contract rejects atomically without changing authoritative state", () => {
  const authoritative = context();
  const before = structuredClone(authoritative);
  const invalid = validSubmission();
  invalid.outcomes = [{
    resultId: otherResultId,
    coverage: "SUPPORTED",
    category: "FACT",
    evidenceIds: [evidenceId],
    summary: "An out-of-scope result must not be retained.",
  }];

  const contract = createRuntimeTurnContract(authoritative);
  const rejected = contract.submit(invalid);

  assert.deepEqual(rejected, { accepted: false, code: "invalid_outcome_scope" });
  assert.deepEqual(contract.context(), before);
});

test("a repaired Runtime Turn commits only the accepted proposal", () => {
  const supplied = context();
  supplied.investigation_results.push({
    result_id: otherResultId,
    required: true,
    coverage: "UNADDRESSED",
    current_outcomes: [],
  });
  const contract = createRuntimeTurnContract(supplied);
  const stale = validSubmission();
  stale.expectedRevision = 99;
  assert.deepEqual(contract.submit(stale), { accepted: false, code: "stale_revision" });

  const accepted = validSubmission();
  accepted.outcomes = [{
    resultId,
    coverage: "SUPPORTED",
    category: "RULE",
    evidenceIds: [evidenceId],
    summary: "The analyst checks the source ledger.",
  }];
  accepted.nextAction = {
    type: "ASK_QUESTION",
    resultId: otherResultId,
    text: "What happens after the source ledger check?",
    responseMode: "FREE_TEXT",
  };
  const result = contract.submit(accepted);

  assert.equal(result.accepted, true);
  assert.equal(result.event, "question_ready");
  assert.equal(result.context.expected_revision, 3);
  assert.deepEqual(result.context.investigation_results[0].current_outcomes, [{
    coverage: "SUPPORTED",
    category: "RULE",
    evidence_ids: [evidenceId],
    summary: "The analyst checks the source ledger.",
  }]);
});

test("a terminal unknown result cannot receive another Question", () => {
  const supplied = context();
  supplied.investigation_results[0].coverage = "UNKNOWN";
  const proposal = validSubmission();
  proposal.nextAction.text = "Who else might know?";

  assert.deepEqual(createRuntimeTurnContract(supplied).submit(proposal), {
    accepted: false,
    code: "terminal_result_cannot_be_queried",
  });
});

test("an identical replay returns the stable accepted state without a second semantic commit", () => {
  const supplied = context();
  supplied.investigation_results.push({
    result_id: otherResultId,
    required: true,
    coverage: "UNADDRESSED",
    current_outcomes: [],
  });
  const contract = createRuntimeTurnContract(supplied);
  const submission = validSubmission();
  submission.outcomes = [{
    resultId,
    coverage: "SUPPORTED",
    category: "RULE",
    evidenceIds: [evidenceId],
    summary: "The analyst checks the source ledger.",
  }];
  submission.nextAction.resultId = otherResultId;

  const accepted = contract.submit(submission);
  const replayed = contract.submit(structuredClone(submission));

  assert.deepEqual(replayed, accepted);
  assert.equal(contract.context().expected_revision, 3);
  assert.equal(contract.context().investigation_results[0].current_outcomes.length, 1);
});

test("changed reuse of a committed run identity is rejected without mutation", () => {
  const contract = createRuntimeTurnContract(context());
  const submission = validSubmission();
  const accepted = contract.submit(submission);
  for (const expectedRevision of [submission.expectedRevision, accepted.context.expected_revision]) {
    const changed = structuredClone(submission);
    changed.expectedRevision = expectedRevision;
    changed.nextAction.text = "A changed question must not commit.";
    assert.deepEqual(contract.submit(changed), { accepted: false, code: "run_identity_reused" });
  }
  assert.deepEqual(contract.context(), accepted.context);
});

test("an unrelated run with a stale or current revision remains rejected after settlement", () => {
  const contract = createRuntimeTurnContract(context());
  const accepted = contract.submit(validSubmission());
  for (const expectedRevision of [2, accepted.context.expected_revision]) {
    const unrelated = validSubmission();
    unrelated.runId = "20000000-0000-0000-0000-000000000099";
    unrelated.expectedRevision = expectedRevision;
    assert.deepEqual(contract.submit(unrelated), { accepted: false, code: "stale_revision" });
  }
  assert.deepEqual(contract.context(), accepted.context);
});

test("unresolved outcomes require Evidence from a Question for the same Investigation Result", () => {
  const supplied = context();
  supplied.investigation_results.push({
    result_id: otherResultId,
    required: true,
    coverage: "UNADDRESSED",
    current_outcomes: [],
  });
  const proposal = validSubmission();
  proposal.outcomes = [{
    resultId: otherResultId,
    coverage: "UNKNOWN",
    evidenceIds: [evidenceId],
    summary: "The owner is unknown.",
  }];
  proposal.nextAction = { type: "PROPOSE_COMPLETION" };

  assert.deepEqual(createRuntimeTurnContract(supplied).submit(proposal), {
    accepted: false,
    code: "unsubstantiated_unresolved_outcome",
  });
});
