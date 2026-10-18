import assert from "node:assert/strict";
import test from "node:test";
import {
  prepareInterviewContext,
  prepareInterviewProposal,
  validateInterviewProposal,
} from "./interview-strategy.mjs";

const currentResultId = "10000000-0000-0000-0000-000000000001";
const nextResultId = "10000000-0000-0000-0000-000000000002";

function context(overrides = {}) {
  return {
    run_id: "20000000-0000-0000-0000-000000000001",
    expected_revision: 7,
    mission: { objective: "Understand the desk's routing rule." },
    shared_mission_entries: [],
    investigation_results: [{
      result_id: currentResultId,
      knowledge_gap: "How does the desk route urgent work?",
      sufficient_evidence: "Trigger, owner, exception, record, and completion signal are explicit.",
      required: true,
      coverage: "EXPLORING",
      current_outcomes: [],
    }, {
      result_id: nextResultId,
      knowledge_gap: "What happens after routing?",
      sufficient_evidence: "Owner and completion signal are explicit.",
      required: true,
      coverage: "UNADDRESSED",
      current_outcomes: [],
    }],
    questions_and_evidence: [{
      question_id: "q1",
      sequence: 1,
      result_id: currentResultId,
      question: "What makes a claim card urgent?",
      evidence_id: "e1",
      source_type: "ANSWER",
      exact_text: "A live leak gets a red ribbon on the claim card.",
    }, {
      question_id: "q2",
      sequence: 2,
      result_id: currentResultId,
      question: "Who owns a red ribbon decision?",
      evidence_id: "e2",
      source_type: "ANSWER",
      exact_text: "The porch keeper owns the red ribbon decision.",
    }, {
      question_id: "q3",
      sequence: 3,
      result_id: currentResultId,
      question: "What is saved after the porch keeper decides?",
      evidence_id: "e3",
      source_type: "ANSWER",
      exact_text: "The saved lane and porch-clock timestamp keep the task open until the audit record is filled.",
    }],
    conversation_state: {
      current_thread_result_id: currentResultId,
      last_question: "What is saved after the porch keeper decides?",
      latest_interviewee_wording: "The saved lane and porch-clock timestamp keep the task open until the audit record is filled.",
    },
    ...overrides,
  };
}

function proposal(nextAction, outcomes = []) {
  return {
    runId: "20000000-0000-0000-0000-000000000001",
    expectedRevision: 7,
    outcomes,
    nextAction,
  };
}

function supportedOutcome(evidenceIds = ["e1", "e2", "e3"]) {
  return {
    resultId: currentResultId,
    coverage: "SUPPORTED",
    category: "RULE",
    evidenceIds,
    summary: "A live leak gets a red ribbon owned by the porch keeper, with a saved lane and porch-clock timestamp.",
  };
}

test("continuity state makes attempted needs and durable provenance explicit", () => {
  const supplied = context();
  supplied.conversation_state.compacted_thread_memory = [{
    result_id: currentResultId,
    question: "What starts the front-porch clock?",
    evidence_id: "compacted-current-evidence",
  }];
  supplied.investigation_results[1].coverage = "SUPPORTED";
  supplied.investigation_results[1].current_outcomes = [{
    coverage: "SUPPORTED",
    category: "RULE",
    summary: "The rescue desk owns the next step.",
    evidence_ids: ["compacted-evidence-1", "compacted-evidence-2"],
  }];

  const prepared = prepareInterviewContext(supplied);

  assert.deepEqual(prepared.conversation_state.continuity.current_thread.evidence_ids, ["compacted-current-evidence", "e1", "e2", "e3"]);
  assert.deepEqual(prepared.conversation_state.continuity.current_thread.attempted_information_needs, [
    "What starts the front-porch clock?",
    "What makes a claim card urgent?",
    "Who owns a red ribbon decision?",
    "What is saved after the porch keeper decides?",
  ]);
  assert.deepEqual(prepared.conversation_state.continuity.settled_outcome_provenance, [{
    result_id: nextResultId,
    evidence_ids: ["compacted-evidence-1", "compacted-evidence-2"],
  }]);
  assert.equal(supplied.conversation_state.continuity, undefined);
});

test("a compacted attempted need is still rejected as a semantic repeat", () => {
  const supplied = context();
  supplied.questions_and_evidence = supplied.questions_and_evidence.slice(-1);
  supplied.conversation_state.compacted_thread_memory = [{
    result_id: currentResultId,
    question: "What makes a claim card urgent?",
    evidence_id: "compacted-current-evidence",
  }];
  const violations = validateInterviewProposal(supplied, proposal({
    type: "ASK_QUESTION",
    resultId: currentResultId,
    text: "What makes the claim card become urgent?",
    responseMode: "FREE_TEXT",
  }));

  assert.ok(violations.some(violation => violation.code === "semantically_repeated_question"));
});

test("a semantic repeat is rejected before it reaches the commitment seam", () => {
  const violations = validateInterviewProposal(context(), proposal({
    type: "ASK_QUESTION",
    resultId: currentResultId,
    text: "Who makes the red ribbon decision?",
    responseMode: "FREE_TEXT",
  }));

  assert.ok(violations.some(violation => violation.code === "semantically_repeated_question"));
});

test("terminal proposals inherit every accepted current-thread Evidence identity", () => {
  const prepared = prepareInterviewProposal(context(), proposal({
    type: "PROPOSE_COMPLETION",
  }, [supportedOutcome(["e3"])]));

  assert.deepEqual(prepared.outcomes[0].evidenceIds, ["e3", "e1", "e2"]);
});

test("an extended supported thread needs a distinct closure answer before transition or completion", () => {
  const transition = validateInterviewProposal(context(), proposal({
    type: "ASK_QUESTION",
    resultId: nextResultId,
    text: "At the rescue desk, who takes ownership next?",
    responseMode: "FREE_TEXT",
  }, [supportedOutcome()]));
  const completion = validateInterviewProposal(context(), proposal(
    { type: "PROPOSE_COMPLETION" },
    [supportedOutcome()],
  ));

  assert.ok(transition.some(violation => violation.code === "supported_thread_needs_closure"));
  assert.ok(completion.some(violation => violation.code === "supported_thread_needs_closure"));
});

test("self-declared completion after an ordinary detail question still requires a distinct synthesis turn", () => {
  const supplied = context();
  supplied.questions_and_evidence.at(-1).exact_text = "The porch step is finished when the lane and clock are saved; an unsaved lane is not complete.";
  supplied.conversation_state.latest_interviewee_wording = supplied.questions_and_evidence.at(-1).exact_text;
  const violations = validateInterviewProposal(supplied, proposal({
    type: "ASK_QUESTION",
    resultId: nextResultId,
    text: "At the rescue desk, who takes ownership next?",
    responseMode: "FREE_TEXT",
  }, [supportedOutcome()]));

  assert.ok(violations.some(violation => violation.code === "supported_thread_needs_closure"));
});

test("terminal thread provenance must represent every accepted answer gathered for that thread", () => {
  const supplied = context();
  supplied.questions_and_evidence.at(-1).question = "What is the complete front-porch rule?";
  supplied.questions_and_evidence.at(-1).exact_text = "That completes the front-porch rule.";
  supplied.conversation_state.last_question = "What is the complete front-porch rule?";
  supplied.conversation_state.latest_interviewee_wording = "That completes the front-porch rule.";
  const violations = validateInterviewProposal(supplied, proposal({
    type: "ASK_QUESTION",
    resultId: nextResultId,
    text: "At the rescue desk, who takes ownership next?",
    responseMode: "FREE_TEXT",
  }, [supportedOutcome(["e2", "e3"])]));

  assert.deepEqual(violations, [{
    code: "incomplete_thread_provenance",
    detail: "Terminal outcomes for the current thread do not represent accepted Evidence: e1",
  }]);
});

test("explicit closure with complete provenance may advance to one new information need", () => {
  const supplied = context();
  supplied.questions_and_evidence.at(-1).question = "What is the complete front-porch rule?";
  supplied.questions_and_evidence.at(-1).exact_text = "That completes the front-porch rule.";
  supplied.conversation_state.last_question = "What is the complete front-porch rule?";
  supplied.conversation_state.latest_interviewee_wording = "That completes the front-porch rule.";
  const violations = validateInterviewProposal(supplied, proposal({
    type: "ASK_QUESTION",
    resultId: nextResultId,
    text: "At the rescue desk, who takes ownership next?",
    responseMode: "FREE_TEXT",
  }, [supportedOutcome()]));

  assert.deepEqual(violations, []);
});

test("a question with more than one interrogative frame is rejected locally", () => {
  const violations = validateInterviewProposal(context(), proposal({
    type: "ASK_QUESTION",
    resultId: currentResultId,
    text: "For the saved lane and porch-clock timestamp, who records it and who checks it?",
    responseMode: "FREE_TEXT",
  }));

  assert.ok(violations.some(violation => violation.code === "multiple_information_needs"));
});

test("a new question must stay grounded in exact wording from the latest accepted answer", () => {
  const ungrounded = validateInterviewProposal(context(), proposal({
    type: "ASK_QUESTION",
    resultId: currentResultId,
    text: "Which employee handles the next workflow step?",
    responseMode: "FREE_TEXT",
  }));
  const paraphrased = context();
  paraphrased.questions_and_evidence = [];
  paraphrased.conversation_state.latest_interviewee_wording = "A live leak gets a red ribbon on the claim card.";
  const changedPunctuation = validateInterviewProposal(paraphrased, proposal({
    type: "ASK_QUESTION",
    resultId: currentResultId,
    text: "Who owns the red-ribbon decision?",
    responseMode: "FREE_TEXT",
  }));

  assert.ok(ungrounded.some(violation => violation.code === "question_ignores_latest_wording"));
  assert.ok(changedPunctuation.some(violation => violation.code === "question_ignores_latest_wording"));
});
