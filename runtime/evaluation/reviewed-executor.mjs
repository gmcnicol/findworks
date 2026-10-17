import { modelIdentityKey } from "./model-identity.mjs";
import { createReviewerRequest, reviewerCadence } from "./review-contract.mjs";

export function createReviewedExecutor({ executeReviewer, executeInterviewer, gate }) {
  const lastReviewBySession = new Map();
  return async context => {
    const cadence = reviewerCadence({ context, lastReview: lastReviewBySession.get(context.session_id) ?? null });
    let interviewerContext = context;
    let review = null;
    let reviewerDurationMs = 0;
    let reviewerUsage = null;
    let reviewerExecutionSucceeded = false;

    if (cadence) {
      const request = createReviewerRequest({ context, trigger: cadence.trigger });
      let proposed;
      try {
        const executed = await executeReviewer(request);
        reviewerExecutionSucceeded = true;
        reviewerDurationMs = executed.durationMs ?? 0;
        reviewerUsage = executed.usage ?? null;
        const proposal = executed.proposal ?? executed.submission;
        proposed = proposal;
        const accepted = gate.accept({ request, proposal, context });
        interviewerContext = accepted.context;
        lastReviewBySession.set(context.session_id, {
          revision: context.expected_revision,
          threadResultId: cadence.threadResultId,
          evidenceCount: cadence.evidenceCount,
        });
        review = {
          accepted: true,
          executionSucceeded: true,
          replayed: accepted.replayed,
          trigger: cadence.trigger,
          requestId: request.requestId,
          proposalId: proposal.proposalId,
          outcomeProposalCount: proposal.outcomeProposals.length,
          completionAssessment: proposal.completionAssessment.status,
        };
      } catch (error) {
        review = {
          accepted: false,
          executionSucceeded: reviewerExecutionSucceeded,
          replayed: false,
          trigger: cadence.trigger,
          requestId: request.requestId,
          proposalId: proposed?.proposalId ?? null,
          rejection: error.message,
          rejectedProposal: proposed ? {
            outcomeProposals: proposed.outcomeProposals?.map(outcome => ({
              resultId: outcome.resultId,
              coverage: outcome.coverage,
              evidenceIds: outcome.evidenceIds,
            })) ?? null,
            nextThreadResultId: proposed.nextThreadProposal?.resultId ?? null,
            completionStatus: proposed.completionAssessment?.status ?? null,
            openRequiredResultIds: proposed.completionAssessment?.openRequiredResultIds ?? null,
          } : null,
        };
      }
    }

    const interviewed = await executeInterviewer(interviewerContext);
    const interviewerDurationMs = interviewed.durationMs ?? 0;
    const interviewerUsage = interviewed.usage ?? null;
    return {
      ...interviewed,
      durationMs: reviewerDurationMs + interviewerDurationMs,
      usage: mergeUsage(reviewerUsage, interviewerUsage),
      reviewerDurationMs,
      interviewerDurationMs,
      reviewerUsage,
      interviewerUsage,
      review,
    };
  };
}

export function compareReviewStrategies({ baseline, reviewed }) {
  const baselineFailures = baseline.failureCounts ?? {};
  const reviewedFailures = reviewed.failureCounts ?? {};
  const usefulDelta = difference(reviewed.usefulEvidencePerQuestion, baseline.usefulEvidencePerQuestion);
  const missedConflictDelta = difference(reviewedFailures.contradictionsMissed, baselineFailures.contradictionsMissed);
  const completionErrorDelta = difference(reviewedFailures.completionErrors, baselineFailures.completionErrors);
  const costRatio = ratio(
    reviewed.distributions?.costUsdPerQuestion?.p50,
    baseline.distributions?.costUsdPerQuestion?.p50,
  );
  const latencyRatio = ratio(reviewed.latencyMs?.p95, baseline.latencyMs?.p95);
  const safetyFields = [
    "deterministicFailures", "semanticGateFailures", "continuityGateFailures",
  ];
  const failureFields = ["contradictionsMissed", "completionErrors", "repeatedQuestions", "earlyCompletions"];
  const noSafetyRegression = safetyFields.every(field => (reviewed[field] ?? 0) <= (baseline[field] ?? 0))
    && failureFields.every(field => (reviewedFailures[field] ?? 0) <= (baselineFailures[field] ?? 0));
  const reliabilityGain = safetyFields.some(field => (reviewed[field] ?? 0) < (baseline[field] ?? 0))
    || failureFields.some(field => (reviewedFailures[field] ?? 0) < (baselineFailures[field] ?? 0));
  const evidenceProtected = typeof usefulDelta === "number" && usefulDelta >= 0;
  const operationallyBounded = typeof costRatio === "number" && costRatio <= 2
    && typeof latencyRatio === "number" && latencyRatio <= 2;
  const decision = noSafetyRegression && reliabilityGain && evidenceProtected && operationallyBounded ? "GO" : "NO_GO";
  return {
    usefulEvidencePerQuestionDelta: usefulDelta,
    missedConflictDelta,
    completionErrorDelta,
    costPerQuestionRatio: costRatio,
    p95LatencyRatio: latencyRatio,
    reviewerCadence: {
      invocations: reviewed.reviewer?.invocations ?? 0,
      eligibleTurns: reviewed.reviewer?.eligibleTurns ?? reviewed.turns ?? 0,
      rate: reviewed.reviewer?.cadence ?? 0,
    },
    safeguards: { noSafetyRegression, reliabilityGain, evidenceProtected, operationallyBounded },
    decision,
    rationale: decision === "GO"
      ? "Periodic review improved at least one reliability signal without reducing Evidence yield or exceeding 2× median cost/question or p95 latency."
      : "Periodic review did not improve reliability within the no-regression, Evidence-yield, 2× cost, and 2× latency gates.",
  };
}

function mergeUsage(...values) {
  if (values.every(value => !value)) return null;
  const result = {};
  for (const field of ["inputTokens", "outputTokens", "cacheWriteTokens", "costUsd"]) {
    const available = values.map(value => value?.[field]).filter(value => typeof value === "number");
    result[field] = available.length === 0 ? null : available.reduce((total, value) => total + value, 0);
  }
  const supplied = values.filter(Boolean);
  const identities = supplied.map(value => value.modelIdentity);
  if (identities.every(Boolean)) {
    const unique = new Map(identities.map(identity => [modelIdentityKey(identity), identity]));
    result.modelIdentity = unique.size === 1 ? structuredClone(identities[0]) : null;
  }
  return result;
}

function difference(value, baseline) {
  return typeof value === "number" && typeof baseline === "number" ? value - baseline : null;
}

function ratio(value, baseline) {
  return typeof value === "number" && typeof baseline === "number" && baseline > 0
    ? value / baseline
    : null;
}
