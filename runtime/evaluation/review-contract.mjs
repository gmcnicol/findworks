import { createHash } from "node:crypto";

const COVERAGES = new Set(["SUPPORTED", "UNKNOWN", "CONFLICT", "ASSUMPTION", "OWNERSHIP_GAP"]);
const CATEGORIES = new Set(["FACT", "RULE", "DECISION", "TERM", "EXCEPTION", "ASSUMPTION"]);
const OPEN_COVERAGES = new Set(["UNADDRESSED", "EXPLORING", "PARTIAL"]);
const UNCERTAINTY_SOURCES = new Set(["DONT_KNOW", "OWNER_GAP", "DECLINED"]);

const RESPONSE_FIELDS = new Set([
  "schemaVersion", "requestId", "proposalId", "sessionId", "missionVersion", "expectedRevision",
  "evidenceRevision", "outcomeProposals", "nextThreadProposal", "completionAssessment",
]);
const OUTCOME_FIELDS = new Set(["resultId", "coverage", "category", "evidenceIds", "summary"]);
const NEXT_THREAD_FIELDS = new Set(["resultId", "reason", "evidenceIds"]);
const COMPLETION_FIELDS = new Set(["status", "openRequiredResultIds", "reason", "evidenceIds"]);

export function createReviewerRequest({ context, trigger }) {
  if (!context || !["INITIAL_EVIDENCE", "PERIODIC", "UNCERTAINTY", "THREAD_BOUNDARY"].includes(trigger)) {
    throw new Error("reviewer_invalid_request");
  }
  const projection = authoritativeProjection(context);
  const evidenceRevision = digest(projection);
  return deepFreeze({
    schemaVersion: 1,
    requestId: `review-${digest({
      sessionId: context.session_id,
      missionVersion: context.mission_version,
      expectedRevision: context.expected_revision,
      evidenceRevision,
      trigger,
    }).slice(0, 32)}`,
    sessionId: context.session_id,
    missionVersion: context.mission_version,
    expectedRevision: context.expected_revision,
    evidenceRevision,
    trigger,
    responsibility: "Inspect immutable Session Evidence, unresolved required coverage, possible conflicts, sufficiency, and next-thread priority. Return advisory proposals only; do not ask the interviewee a Question or mutate Mission, Evidence, outcomes, or completion state.",
    ...projection,
  });
}

export class ReviewerProposalGate {
  #accepted = new Map();

  accept({ request, proposal, context }) {
    validateFreshRequest(request, context);
    validateProposal(request, proposal);
    const identity = `${request.requestId}:${proposal.proposalId}`;
    const fingerprint = digest(proposal);
    const existing = this.#accepted.get(identity);
    if (existing) {
      if (existing.fingerprint !== fingerprint) throw new Error("reviewer_idempotency_conflict");
      return { replayed: true, context: attachGuidance(context, existing.proposal) };
    }
    const stored = structuredClone(proposal);
    this.#accepted.set(identity, { fingerprint, proposal: stored });
    return { replayed: false, context: attachGuidance(context, stored) };
  }
}

export function reviewerCadence({ context, lastReview }) {
  const evidence = evidenceRecords(context);
  if (evidence.length === 0) return null;
  const threadResultId = context.conversation_state?.current_thread_result_id ?? null;
  if (!lastReview) return { trigger: "INITIAL_EVIDENCE", evidenceCount: evidence.length, threadResultId };
  const latest = evidence.at(-1);
  if (UNCERTAINTY_SOURCES.has(latest?.source_type)) {
    return { trigger: "UNCERTAINTY", evidenceCount: evidence.length, threadResultId };
  }
  if (threadResultId && lastReview.threadResultId && threadResultId !== lastReview.threadResultId) {
    return { trigger: "THREAD_BOUNDARY", evidenceCount: evidence.length, threadResultId };
  }
  if (context.expected_revision - lastReview.revision >= 4) {
    return { trigger: "PERIODIC", evidenceCount: evidence.length, threadResultId };
  }
  return null;
}

function validateFreshRequest(request, context) {
  if (request.sessionId !== context.session_id) throw new Error("reviewer_result_scope");
  if (request.missionVersion !== context.mission_version) throw new Error("reviewer_mission_stale");
  if (request.expectedRevision !== context.expected_revision) throw new Error("reviewer_revision_stale");
  if (request.evidenceRevision !== digest(authoritativeProjection(context))) throw new Error("reviewer_evidence_stale");
}

function validateProposal(request, proposal) {
  assertObject(proposal, "reviewer_invalid_proposal");
  rejectUnknownFields(proposal, RESPONSE_FIELDS);
  if (proposal.schemaVersion !== 1 || proposal.requestId !== request.requestId
      || typeof proposal.proposalId !== "string" || proposal.proposalId.trim() === "" || proposal.proposalId.length > 200) {
    throw new Error("reviewer_invalid_proposal");
  }
  if (proposal.sessionId !== request.sessionId) throw new Error("reviewer_result_scope");
  if (proposal.missionVersion !== request.missionVersion) throw new Error("reviewer_mission_stale");
  if (proposal.expectedRevision !== request.expectedRevision) throw new Error("reviewer_revision_stale");
  if (proposal.evidenceRevision !== request.evidenceRevision) throw new Error("reviewer_evidence_stale");

  const results = new Map(request.investigationResults.map(result => [result.result_id, result]));
  const evidenceIds = new Set(request.questionsAndEvidence.map(record => record.evidence_id).filter(Boolean));
  if (!Array.isArray(proposal.outcomeProposals)) throw new Error("reviewer_invalid_proposal");
  for (const outcome of proposal.outcomeProposals) {
    assertObject(outcome, "reviewer_invalid_proposal");
    rejectUnknownFields(outcome, OUTCOME_FIELDS);
    if (!results.has(outcome.resultId)) throw new Error("reviewer_result_scope");
    if (!COVERAGES.has(outcome.coverage)) throw new Error("reviewer_invalid_proposal");
    const category = outcome.category ?? (outcome.coverage === "ASSUMPTION" ? "ASSUMPTION" : "FACT");
    if (["SUPPORTED", "ASSUMPTION"].includes(outcome.coverage) && !CATEGORIES.has(category)) {
      throw new Error("reviewer_invalid_proposal");
    }
    validateText(outcome.summary);
    validateEvidenceIds(outcome.evidenceIds, evidenceIds);
  }

  const projectedCoverage = new Map(request.investigationResults.map(result => [result.result_id, result.coverage]));
  for (const outcome of proposal.outcomeProposals) {
    const current = projectedCoverage.get(outcome.resultId);
    if (!new Set(["SUPPORTED", "ASSUMPTION", "CONFLICT"]).has(current) || outcome.coverage === "CONFLICT") {
      projectedCoverage.set(outcome.resultId, outcome.coverage);
    }
  }
  if (proposal.nextThreadProposal !== null) {
    assertObject(proposal.nextThreadProposal, "reviewer_invalid_proposal");
    rejectUnknownFields(proposal.nextThreadProposal, NEXT_THREAD_FIELDS);
    if (!results.has(proposal.nextThreadProposal.resultId)
        || !OPEN_COVERAGES.has(projectedCoverage.get(proposal.nextThreadProposal.resultId))) {
      throw new Error("reviewer_result_scope");
    }
    validateText(proposal.nextThreadProposal.reason);
    validateEvidenceIds(proposal.nextThreadProposal.evidenceIds, evidenceIds, true);
  }

  const assessment = proposal.completionAssessment;
  assertObject(assessment, "reviewer_invalid_proposal");
  rejectUnknownFields(assessment, COMPLETION_FIELDS);
  if (!["READY", "NOT_READY"].includes(assessment.status)
      || !Array.isArray(assessment.openRequiredResultIds)) throw new Error("reviewer_invalid_proposal");
  validateText(assessment.reason);
  validateEvidenceIds(assessment.evidenceIds, evidenceIds, true);
  const openRequired = request.investigationResults
    .filter(result => result.required && OPEN_COVERAGES.has(projectedCoverage.get(result.result_id)))
    .map(result => result.result_id).sort();
  const claimedOpen = [...new Set(assessment.openRequiredResultIds)].sort();
  if (claimedOpen.some(id => !results.has(id))) throw new Error("reviewer_result_scope");
  if (assessment.status === "READY" && openRequired.length > 0) throw new Error("reviewer_premature_completion");
  if (assessment.status === "NOT_READY" && JSON.stringify(claimedOpen) !== JSON.stringify(openRequired)) {
    throw new Error("reviewer_completion_scope");
  }
  if (assessment.status === "READY" && claimedOpen.length > 0) throw new Error("reviewer_completion_scope");
}

function attachGuidance(context, proposal) {
  const enriched = structuredClone(context);
  enriched.reviewer_guidance = {
    responsibility: "ADVISORY_ONLY",
    proposal_id: proposal.proposalId,
    request_id: proposal.requestId,
    expected_revision: proposal.expectedRevision,
    evidence_revision: proposal.evidenceRevision,
    outcome_proposals: structuredClone(proposal.outcomeProposals),
    next_thread_proposal: structuredClone(proposal.nextThreadProposal),
    completion_assessment: structuredClone(proposal.completionAssessment),
  };
  return enriched;
}

function authoritativeProjection(context) {
  return {
    mission: structuredClone(context.mission ?? {}),
    sharedMissionEntries: structuredClone(context.shared_mission_entries ?? []),
    investigationResults: structuredClone(context.investigation_results ?? []),
    questionsAndEvidence: structuredClone(context.questions_and_evidence ?? []),
    conversationState: structuredClone(context.conversation_state ?? {}),
  };
}

function evidenceRecords(context) {
  return (context.questions_and_evidence ?? []).filter(record => record.evidence_id);
}

function validateEvidenceIds(ids, supplied, allowEmpty = false) {
  if (!Array.isArray(ids) || (!allowEmpty && ids.length === 0)
      || ids.some(id => typeof id !== "string" || !supplied.has(id))) {
    throw new Error("reviewer_provenance_scope");
  }
}

function validateText(value) {
  if (typeof value !== "string" || value.trim() === "" || value.length > 2_000) {
    throw new Error("reviewer_invalid_proposal");
  }
}

function assertObject(value, code) {
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(code);
}

function rejectUnknownFields(value, allowed) {
  if (Object.keys(value).some(key => !allowed.has(key))) throw new Error("reviewer_unknown_field");
}

function digest(value) {
  return createHash("sha256").update(canonicalJson(value)).digest("hex");
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value && typeof value === "object") {
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function deepFreeze(value) {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    Object.freeze(value);
    for (const child of Object.values(value)) deepFreeze(child);
  }
  return value;
}
