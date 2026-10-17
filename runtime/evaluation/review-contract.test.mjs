import assert from "node:assert/strict";
import test from "node:test";
import {
  ReviewerProposalGate,
  createReviewerRequest,
  reviewerCadence,
} from "./review-contract.mjs";

const resultId = "10000000-0000-0000-0000-000000000001";
const evidenceId = "40000000-0000-0000-0000-000000000001";

function context() {
  return {
    run_id: "20000000-0000-0000-0000-000000000001",
    session_id: "30000000-0000-0000-0000-000000000001",
    mission_version: 3,
    expected_revision: 7,
    mission: { objective: "Understand exception triage", desired_outcome: "Grounded rules", data_use: "Discovery" },
    shared_mission_entries: [{ kind: "BOUNDARY", position: 0, value: "Do not discuss payroll" }],
    investigation_results: [{
      result_id: resultId,
      knowledge_gap: "Understand the escalation rule",
      required: true,
      coverage: "EXPLORING",
      sufficient_evidence: "Trigger and owner",
      current_outcomes: [],
    }],
    questions_and_evidence: [{
      question_id: "50000000-0000-0000-0000-000000000001",
      sequence: 1,
      question: "What happens when a case is late?",
      active: false,
      evidence_id: evidenceId,
      source_type: "ANSWER",
      exact_text: "Maya checks it, but the handbook says Lee owns it.",
    }],
    conversation_state: {
      current_thread_result_id: resultId,
      latest_interviewee_wording: "Maya checks it, but the handbook says Lee owns it.",
    },
  };
}

function proposal(request) {
  return {
    schemaVersion: 1,
    requestId: request.requestId,
    proposalId: "review-proposal-7",
    sessionId: request.sessionId,
    missionVersion: request.missionVersion,
    expectedRevision: request.expectedRevision,
    evidenceRevision: request.evidenceRevision,
    outcomeProposals: [{
      resultId,
      coverage: "CONFLICT",
      evidenceIds: [evidenceId],
      summary: "The answer attributes checking to Maya and ownership to Lee without reconciling the roles.",
    }],
    nextThreadProposal: null,
    completionAssessment: {
      status: "READY",
      openRequiredResultIds: [],
      reason: "The proposed Conflict is an honest terminal outcome.",
      evidenceIds: [evidenceId],
    },
  };
}

test("a validated reviewer proposal becomes advisory interviewer context without mutating authoritative state", () => {
  const authoritative = context();
  const before = structuredClone(authoritative);
  const request = createReviewerRequest({ context: authoritative, trigger: "INITIAL_EVIDENCE" });
  const accepted = new ReviewerProposalGate().accept({ request, proposal: proposal(request), context: authoritative });

  assert.equal(accepted.replayed, false);
  assert.equal(accepted.context.reviewer_guidance.responsibility, "ADVISORY_ONLY");
  assert.equal(accepted.context.reviewer_guidance.proposal_id, "review-proposal-7");
  assert.deepEqual(accepted.context.questions_and_evidence, before.questions_and_evidence);
  assert.deepEqual(authoritative, before);
  assert.equal(Object.isFrozen(request), true);
});

test("stale Mission, revision, Evidence, and out-of-scope provenance reject before context changes", () => {
  const authoritative = context();
  const request = createReviewerRequest({ context: authoritative, trigger: "PERIODIC" });
  for (const mutate of [
    value => { value.missionVersion += 1; },
    value => { value.expectedRevision += 1; },
    value => { value.evidenceRevision = "stale"; },
    value => { value.outcomeProposals[0].evidenceIds = ["unknown-evidence"]; },
    value => { value.nextThreadProposal = { resultId: "unknown-result", reason: "Out of scope", evidenceIds: [] }; },
  ]) {
    const candidate = proposal(request);
    mutate(candidate);
    assert.throws(
      () => new ReviewerProposalGate().accept({ request, proposal: candidate, context: authoritative }),
      /reviewer_(?:mission|revision|evidence|result|provenance)/,
    );
    assert.equal(authoritative.reviewer_guidance, undefined);
  }
});

test("the server gate replays an identical proposal and rejects reuse with different content", () => {
  const authoritative = context();
  const request = createReviewerRequest({ context: authoritative, trigger: "PERIODIC" });
  const gate = new ReviewerProposalGate();
  const first = gate.accept({ request, proposal: proposal(request), context: authoritative });
  const replay = gate.accept({ request, proposal: proposal(request), context: authoritative });
  assert.equal(replay.replayed, true);
  assert.deepEqual(replay.context.reviewer_guidance, first.context.reviewer_guidance);

  const conflicting = proposal(request);
  conflicting.completionAssessment.reason = "Different payload under the same identity.";
  assert.throws(
    () => gate.accept({ request, proposal: conflicting, context: authoritative }),
    /reviewer_idempotency_conflict/,
  );
});

test("reviewer cannot ask Questions, rewrite Evidence, or claim completion while required work remains open", () => {
  const authoritative = context();
  const request = createReviewerRequest({ context: authoritative, trigger: "PERIODIC" });

  const interviewerWork = { ...proposal(request), question: "Who owns it?" };
  assert.throws(
    () => new ReviewerProposalGate().accept({ request, proposal: interviewerWork, context: authoritative }),
    /reviewer_unknown_field/,
  );

  const rewritten = proposal(request);
  rewritten.outcomeProposals[0].exactText = "Changed answer";
  assert.throws(
    () => new ReviewerProposalGate().accept({ request, proposal: rewritten, context: authoritative }),
    /reviewer_unknown_field/,
  );

  const premature = proposal(request);
  premature.outcomeProposals = [];
  premature.completionAssessment = {
    status: "READY",
    openRequiredResultIds: [],
    reason: "Done.",
    evidenceIds: [evidenceId],
  };
  assert.throws(
    () => new ReviewerProposalGate().accept({ request, proposal: premature, context: authoritative }),
    /reviewer_premature_completion/,
  );
});

test("cadence reviews initial Evidence, uncertainty, thread changes, and every fourth unreviewed answer", () => {
  const supplied = context();
  assert.equal(reviewerCadence({ context: supplied, lastReview: null }).trigger, "INITIAL_EVIDENCE");

  const lastReview = { revision: 7, threadResultId: resultId, evidenceCount: 1 };
  supplied.expected_revision = 8;
  supplied.questions_and_evidence.push({ evidence_id: "e2", source_type: "ANSWER", exact_text: "More detail" });
  assert.equal(reviewerCadence({ context: supplied, lastReview }), null);

  supplied.questions_and_evidence.push(
    { evidence_id: "e3", source_type: "ANSWER", exact_text: "More" },
    { evidence_id: "e4", source_type: "ANSWER", exact_text: "More" },
    { evidence_id: "e5", source_type: "ANSWER", exact_text: "More" },
  );
  supplied.expected_revision = 11;
  assert.equal(reviewerCadence({ context: supplied, lastReview }).trigger, "PERIODIC");

  const uncertain = context();
  uncertain.questions_and_evidence.push({ evidence_id: "e2", source_type: "DONT_KNOW", exact_text: "I do not know" });
  assert.equal(reviewerCadence({ context: uncertain, lastReview }).trigger, "UNCERTAINTY");

  const changed = context();
  changed.conversation_state.current_thread_result_id = "10000000-0000-0000-0000-000000000099";
  assert.equal(reviewerCadence({ context: changed, lastReview }).trigger, "THREAD_BOUNDARY");
});
