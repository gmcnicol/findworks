const OPEN_COVERAGES = new Set(["UNADDRESSED", "EXPLORING", "PARTIAL"]);
const TERMINAL_COVERAGES = new Set(["SUPPORTED", "UNKNOWN", "CONFLICT", "ASSUMPTION", "OWNERSHIP_GAP"]);
const QUESTION_STOP_WORDS = new Set([
  "a", "after", "an", "and", "are", "at", "be", "before", "besides", "by", "can", "could", "describe", "did", "do", "does", "earlier", "finally", "first", "for", "from", "get", "gets",
  "has", "have", "how", "i", "if", "in", "is", "it", "next", "of", "on", "once", "or", "please", "should", "sorry", "tell", "that", "the", "then", "this", "to", "was", "were", "what",
  "when", "where", "which", "who", "why", "with", "would", "you", "your",
]);

export function prepareInterviewContext(context) {
  const prepared = structuredClone(context);
  prepared.conversation_state ??= {};
  const currentResultId = prepared.conversation_state.current_thread_result_id ?? null;
  const currentRecords = threadMemoryForResult(prepared, currentResultId);
  prepared.conversation_state.continuity = {
    current_thread: currentResultId ? {
      result_id: currentResultId,
      attempted_information_needs: currentRecords.map(record => record.question).filter(Boolean),
      evidence_ids: currentRecords.map(record => record.evidence_id).filter(Boolean),
      accepted_answer_count: currentRecords.filter(record => record.evidence_id).length,
      latest_evidence_id: currentRecords.findLast(record => record.evidence_id)?.evidence_id ?? null,
    } : null,
    settled_outcome_provenance: prepared.investigation_results.flatMap(result => {
      const evidenceIds = [...new Set((result.current_outcomes ?? []).flatMap(outcome => outcome.evidence_ids ?? []))];
      return TERMINAL_COVERAGES.has(result.coverage) && evidenceIds.length > 0
        ? [{ result_id: result.result_id, evidence_ids: evidenceIds }]
        : [];
    }),
  };
  return prepared;
}

export function prepareInterviewProposal(context, proposal) {
  const prepared = structuredClone(proposal);
  const action = prepared?.nextAction;
  const currentResultId = context.conversation_state?.current_thread_result_id ?? null;
  const submittedCurrentOutcomes = (prepared?.outcomes ?? []).filter(outcome => outcome.resultId === currentResultId
    && TERMINAL_COVERAGES.has(outcome.coverage));
  const advancesThread = action?.type === "PROPOSE_COMPLETION"
    || (action?.type === "ASK_QUESTION" && currentResultId && action.resultId !== currentResultId);
  if (submittedCurrentOutcomes.length > 0 && advancesThread) {
    const represented = new Set(submittedCurrentOutcomes.flatMap(outcome => outcome.evidenceIds ?? []));
    const missing = threadMemoryForResult(context, currentResultId)
      .map(record => record.evidence_id).filter(Boolean).filter(id => !represented.has(id));
    submittedCurrentOutcomes[0].evidenceIds = [...(submittedCurrentOutcomes[0].evidenceIds ?? []), ...missing];
  }
  return prepared;
}

export function validateInterviewProposal(context, proposal) {
  if (!proposal || typeof proposal !== "object") return [{ code: "invalid_turn", detail: "Turn proposal must be an object" }];
  const action = proposal.nextAction;
  if (!action || !["ASK_QUESTION", "PROPOSE_COMPLETION"].includes(action.type)) return [];

  const violations = [];
  const currentResultId = context.conversation_state?.current_thread_result_id ?? null;
  const currentResult = context.investigation_results?.find(result => result.result_id === currentResultId);
  const currentRecords = threadMemoryForResult(context, currentResultId);
  const submittedCurrentOutcomes = (proposal.outcomes ?? []).filter(outcome => outcome.resultId === currentResultId);
  const closesCurrentThread = submittedCurrentOutcomes.some(outcome => TERMINAL_COVERAGES.has(outcome.coverage));
  const advancesThread = action.type === "PROPOSE_COMPLETION"
    || (action.type === "ASK_QUESTION" && currentResultId && action.resultId !== currentResultId);

  if (closesCurrentThread && advancesThread) {
    const represented = new Set([
      ...(currentResult?.current_outcomes ?? []).flatMap(outcome => outcome.evidence_ids ?? []),
      ...submittedCurrentOutcomes.flatMap(outcome => outcome.evidenceIds ?? []),
    ]);
    const missing = currentRecords.map(record => record.evidence_id).filter(Boolean).filter(id => !represented.has(id));
    if (missing.length > 0) violations.push({
      code: "incomplete_thread_provenance",
      detail: `Terminal outcomes for the current thread do not represent accepted Evidence: ${missing.join(", ")}`,
    });

    const supported = submittedCurrentOutcomes.some(outcome => outcome.coverage === "SUPPORTED");
    if (supported && currentRecords.length >= 3 && !hasExplicitClosure(context, currentRecords)) {
      violations.push({
        code: "supported_thread_needs_closure",
        detail: "An extended supported thread needs a distinct accepted closure or synthesis answer before transition or completion",
      });
    }
  }

  if (action.type === "ASK_QUESTION") {
    const previousQuestions = [
      ...(context.conversation_state?.compacted_thread_memory ?? []).map(record => record.question),
      ...(context.questions_and_evidence ?? []).map(record => record.question),
    ].filter(Boolean);
    if (previousQuestions.some(question => questionsSemanticallyRepeat(question, action.text))) {
      violations.push({
        code: "semantically_repeated_question",
        detail: "The proposed Question repeats an information need already attempted in this Session",
      });
    }
    const bundled = /\b(?:and|or)\s+(?:what|who|when|where|why|how|does|do|is|are|should|would|can)\b/i.test(action.text);
    if (bundled) {
      violations.push({
        code: "multiple_information_needs",
        detail: "The proposed Question contains a second interrogative frame",
      });
    }
    if ((!currentResultId || action.resultId === currentResultId)
        && !usesLatestIntervieweeWording(context.conversation_state?.latest_interviewee_wording, action.text)) {
      violations.push({
        code: "question_ignores_latest_wording",
        detail: "The proposed Question does not use any distinctive wording from the latest accepted answer",
      });
    }
  }

  return violations;
}

export function questionsSemanticallyRepeat(first, second) {
  if (normaliseQuestion(first) === normaliseQuestion(second)) return true;
  const firstWords = new Set(contentWords(first));
  const secondWords = new Set(contentWords(second));
  if (firstWords.size < 3 || secondWords.size < 3) return false;
  const intersection = [...firstWords].filter(word => secondWords.has(word)).length;
  const overlap = intersection / Math.min(firstWords.size, secondWords.size);
  const union = new Set([...firstWords, ...secondWords]).size;
  return overlap >= 0.72 || intersection / union >= 0.55;
}

export function normaliseQuestion(value) {
  return String(value ?? "").toLowerCase().replace(/[^a-z0-9 ]/g, "").replace(/\s+/g, " ").trim();
}

export function contentWords(value) {
  return String(value ?? "").toLowerCase().match(/[a-z0-9]+/g)?.map(stemWord)
    .filter(word => word.length > 1 && !QUESTION_STOP_WORDS.has(word)) ?? [];
}

export function stemWord(word) {
  if (word.endsWith("ies") && word.length > 4) return `${word.slice(0, -3)}y`;
  if (word.endsWith("ing") && word.length > 5) return word.slice(0, -3);
  if (word.endsWith("ed") && word.length > 4) return word.slice(0, -2);
  if (word.endsWith("es") && word.length > 4) return word.slice(0, -2);
  if (word.endsWith("s") && word.length > 3) return word.slice(0, -1);
  return word;
}

function threadMemoryForResult(context, resultId) {
  if (!resultId) return [];
  const compacted = (context.conversation_state?.compacted_thread_memory ?? [])
    .filter(record => record.result_id === resultId);
  const records = (context.questions_and_evidence ?? []).filter(record => record.evidence_id);
  const explicitlyScoped = records.filter(record => record.result_id === resultId);
  if (explicitlyScoped.length > 0 || records.some(record => record.result_id)) return [...compacted, ...explicitlyScoped];
  return [...compacted, ...records];
}

function hasExplicitClosure(context, currentRecords) {
  const latestAnswer = String(context.conversation_state?.latest_interviewee_wording
    ?? currentRecords.findLast(record => record.evidence_id)?.exact_text ?? "");
  const lastQuestion = String(context.conversation_state?.last_question
    ?? currentRecords.findLast(record => record.question)?.question ?? "");
  const askedForClosure = /\b(?:complete|full|anything else|nothing else|whole rule|entire rule|any correction)\b/i.test(lastQuestion);
  const answerClosed = /\b(?:complete[sd]?|done|closes?|nothing else|no other)\b/i.test(latestAnswer);
  const answerExplicitlySynthesises = /\b(?:that (?:completes|is the complete)|(?:rule|check|round|puzzle|pass|handoff) (?:is )?(?:complete|done)|round closes)\b/i.test(latestAnswer);
  return answerExplicitlySynthesises || (askedForClosure && answerClosed);
}

function usesLatestIntervieweeWording(latestAnswer, question) {
  const answerTokens = String(latestAnswer ?? "").toLowerCase().match(/[a-z0-9]+/g) ?? [];
  const questionText = String(question ?? "").toLowerCase();
  const phrases = [];
  for (let start = 0; start < answerTokens.length; start++) {
    for (let length = 2; length <= 4 && start + length <= answerTokens.length; length++) {
      const tokens = answerTokens.slice(start, start + length);
      const distinctive = tokens.filter(token => {
        const stemmed = stemWord(token);
        return stemmed.length >= 3 && !QUESTION_STOP_WORDS.has(stemmed);
      });
      if (distinctive.length >= 2) phrases.push(tokens.join(" "));
    }
  }
  if (phrases.length > 0) return phrases.some(phrase => questionText.includes(phrase));
  const distinctiveWords = answerTokens.filter(token => token.length >= 4 && !QUESTION_STOP_WORDS.has(stemWord(token)));
  return distinctiveWords.length === 0 || distinctiveWords.some(word => new RegExp(`\\b${word}\\b`, "i").test(questionText));
}
