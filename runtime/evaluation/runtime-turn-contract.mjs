const COVERAGES = new Set(["SUPPORTED", "UNKNOWN", "CONFLICT", "ASSUMPTION", "OWNERSHIP_GAP"]);
const UNRESOLVED_COVERAGES = new Set(["UNKNOWN", "CONFLICT", "OWNERSHIP_GAP"]);
const CATEGORIES = new Set(["FACT", "RULE", "DECISION", "TERM", "EXCEPTION", "ASSUMPTION"]);
const RESPONSE_MODES = new Set(["FREE_TEXT", "YES_NO", "YES_NO_PARTLY", "PARAPHRASE", "CHOICE"]);
const OPEN_COVERAGES = new Set(["UNADDRESSED", "EXPLORING", "PARTIAL"]);

/**
 * In-memory adapter for the production Runtime Turn interface. It mirrors the
 * atomic validation and mutation rules of RuntimeTurnController while keeping
 * synthetic evaluation state behind one small submit interface.
 */
export function applyAcceptedRuntimeOutcomes(context, outcomes) {
  return commitOutcomes(context, outcomes);
}

export function createRuntimeTurnContract(initialContext) {
  let authoritative = structuredClone(initialContext);
  const settlements = new Map();
  return {
    context: () => structuredClone(authoritative),
    submit(submission) {
      const settled = settlements.get(submission?.runId);
      if (settled) {
        if (settled.submission !== canonicalJson(submission)) {
          return { accepted: false, code: "run_identity_reused" };
        }
        return structuredClone(settled.result);
      }
      const validation = validate(authoritative, submission);
      if (validation) return { accepted: false, code: validation };
      authoritative = commit(authoritative, submission);
      const event = submission.nextAction.type === "PROPOSE_COMPLETION"
        ? "completion_confirmation_ready"
        : "question_ready";
      const result = { accepted: true, event, context: structuredClone(authoritative) };
      settlements.set(submission.runId, {
        submission: canonicalJson(submission),
        result: structuredClone(result),
      });
      return result;
    },
  };
}

function validate(context, turn) {
  if (!turn || typeof turn !== "object" || Array.isArray(turn)) return "invalid_turn";
  if (turn.runId !== context.run_id || turn.expectedRevision !== context.expected_revision) return "stale_revision";
  const outcomes = turn.outcomes == null ? [] : turn.outcomes;
  if (!Array.isArray(outcomes)) return "invalid_outcome";
  const resultIds = new Set(context.investigation_results.map(result => result.result_id));
  const evidenceRecords = [
    ...(context.conversation_state?.compacted_thread_memory ?? []),
    ...(context.questions_and_evidence ?? []),
  ];
  const evidenceIds = new Set([
    ...evidenceRecords.map(record => record.evidence_id).filter(Boolean),
    ...context.investigation_results.flatMap(result => (result.current_outcomes ?? [])
      .flatMap(outcome => outcome.evidence_ids ?? [])),
  ]);

  for (const outcome of outcomes) {
    if (UNRESOLVED_COVERAGES.has(outcome?.coverage)) {
      const directlyExplored = (outcome.evidenceIds ?? []).some(id => evidenceRecords
        .some(record => record.evidence_id === id && record.result_id === outcome.resultId));
      if (!directlyExplored) return "unsubstantiated_unresolved_outcome";
    }
  }
  for (const outcome of outcomes) {
    if (!COVERAGES.has(outcome?.coverage)) return "invalid_outcome";
    if (typeof outcome.summary !== "string" || outcome.summary.trim() === "" || outcome.summary.length > 2_000
        || !Array.isArray(outcome.evidenceIds) || outcome.evidenceIds.length === 0
        || outcome.evidenceIds.some(id => !evidenceIds.has(id))) return "invalid_outcome_evidence";
    const category = outcome.coverage === "ASSUMPTION" ? "ASSUMPTION" : outcome.category ?? "FACT";
    if ((outcome.coverage === "SUPPORTED" || outcome.coverage === "ASSUMPTION") && !CATEGORIES.has(category)) {
      return "invalid_knowledge_category";
    }
    if (!resultIds.has(outcome.resultId)) return "invalid_outcome_scope";
  }

  const projected = commitOutcomes(context, outcomes);
  const action = turn.nextAction;
  if (!action || !["ASK_QUESTION", "PROPOSE_COMPLETION"].includes(action.type)) {
    return "exactly_one_next_action_required";
  }
  if (action.type === "ASK_QUESTION") {
    if (typeof action.text !== "string" || action.text.trim() === "" || action.text.length > 2_000) return "invalid_question";
    const currentThreadId = context.conversation_state?.current_thread_result_id;
    const currentThread = projected.investigation_results.find(result => result.result_id === currentThreadId);
    if (currentThread && OPEN_COVERAGES.has(currentThread.coverage) && action.resultId !== currentThreadId) {
      return "current_thread_still_open";
    }
    if (!resultIds.has(action.resultId)) return "invalid_question_scope";
    const mode = action.responseMode ?? "FREE_TEXT";
    if (!RESPONSE_MODES.has(mode)) return "invalid_response_mode";
    const options = mode === "CHOICE" ? action.options ?? [] : [];
    if (mode === "CHOICE" && (options.length < 2 || options.length > 5)) return "invalid_options";
    if (options.some(option => typeof option?.id !== "string" || option.id.trim() === "" || option.id.length > 80
        || typeof option?.label !== "string" || option.label.trim() === "" || option.label.length > 200)
        || new Set(options.map(option => option.id)).size !== options.length) return "invalid_options";
  } else {
    const incomplete = projected.investigation_results.some(result => result.required && OPEN_COVERAGES.has(result.coverage));
    if (incomplete) return "premature_completion";
  }
  return null;
}

function commit(context, submission) {
  const updated = commitOutcomes(context, submission.outcomes ?? []);
  updated.expected_revision += 1;
  return updated;
}

function canonicalJson(value) {
  return JSON.stringify(value, (_, candidate) => candidate && typeof candidate === "object" && !Array.isArray(candidate)
    ? Object.fromEntries(Object.entries(candidate).sort(([left], [right]) => left.localeCompare(right)))
    : candidate);
}

function commitOutcomes(context, outcomes) {
  const updated = structuredClone(context);
  for (const outcome of outcomes) {
    const result = updated.investigation_results.find(candidate => candidate.result_id === outcome.resultId);
    if (!result) continue;
    if (!["SUPPORTED", "ASSUMPTION"].includes(result.coverage)) result.coverage = outcome.coverage;
    result.current_outcomes ??= [];
    const category = outcome.coverage === "ASSUMPTION" ? "ASSUMPTION" : outcome.category ?? "FACT";
    const summary = outcome.summary.trim();
    const existing = result.current_outcomes.find(candidate => candidate.coverage === outcome.coverage
      && (candidate.category ?? "FACT") === category && candidate.summary === summary);
    if (existing) existing.evidence_ids = [...new Set([...(existing.evidence_ids ?? []), ...outcome.evidenceIds])];
    else result.current_outcomes.push({
      coverage: outcome.coverage,
      category,
      summary,
      evidence_ids: [...new Set(outcome.evidenceIds)],
    });
  }
  return updated;
}
