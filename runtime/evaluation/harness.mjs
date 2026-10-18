import { randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";
import { applyAcceptedRuntimeOutcomes } from "./runtime-turn-contract.mjs";

const COVERAGES = new Set(["SUPPORTED", "UNKNOWN", "CONFLICT", "ASSUMPTION", "OWNERSHIP_GAP"]);
const CATEGORIES = new Set(["FACT", "RULE", "DECISION", "TERM", "EXCEPTION", "ASSUMPTION"]);
const RESPONSE_MODES = new Set(["FREE_TEXT", "YES_NO", "YES_NO_PARTLY", "PARAPHRASE", "CHOICE"]);
const OPEN_COVERAGES = new Set(["UNADDRESSED", "EXPLORING", "PARTIAL"]);
const TERMINAL_COVERAGES = new Set(["SUPPORTED", "UNKNOWN", "CONFLICT", "ASSUMPTION", "OWNERSHIP_GAP"]);
const QUESTION_STOP_WORDS = new Set([
  "a", "after", "an", "and", "are", "at", "be", "before", "besides", "by", "can", "could", "describe", "did", "do", "does", "earlier", "finally", "first", "for", "from", "get", "gets",
  "has", "have", "how", "i", "if", "in", "is", "it", "next", "of", "on", "once", "or", "please", "should", "sorry", "tell", "that", "the", "then", "this", "to", "was", "were", "what",
  "when", "where", "which", "who", "why", "with", "would", "you", "your",
]);

export async function loadFixtures(directory) {
  const names = (await readdir(directory)).filter(name => name.endsWith(".json")).sort();
  const fixtures = [];
  for (const name of names) {
    const source = join(directory, name);
    const fixture = JSON.parse(await readFile(source, "utf8"));
    validateFixture(fixture, source);
    fixtures.push(fixture);
  }
  if (fixtures.length === 0) throw new Error(`No evaluation fixtures found in ${directory}`);
  return fixtures;
}

export function validateFixture(fixture, source = "fixture") {
  if (fixture?.schemaVersion !== 1) throw new Error(`${source}: unsupported schemaVersion`);
  for (const field of ["fixtureVersion", "id", "title"]) {
    if (typeof fixture[field] !== "string" || fixture[field].trim() === "") throw new Error(`${source}: missing ${field}`);
  }
  if (!fixture.persona || typeof fixture.persona.name !== "string" || typeof fixture.persona.role !== "string") {
    throw new Error(`${source}: missing persona`);
  }
  if (!fixture.context || !Array.isArray(fixture.context.investigation_results)) throw new Error(`${source}: missing context`);
  if (!Array.isArray(fixture.turns) || fixture.turns.length === 0) throw new Error(`${source}: turns must not be empty`);
  if (fixture.assertions?.minimumTurns !== undefined
      && (!Number.isInteger(fixture.assertions.minimumTurns) || fixture.assertions.minimumTurns < 1
        || fixture.turns.length < fixture.assertions.minimumTurns)) {
    throw new Error(`${source}: turns do not satisfy assertions.minimumTurns`);
  }
  if (fixture.contextPolicy?.maxQuestionEvidenceRecords !== undefined
      && (!Number.isInteger(fixture.contextPolicy.maxQuestionEvidenceRecords)
        || fixture.contextPolicy.maxQuestionEvidenceRecords < 1)) {
    throw new Error(`${source}: contextPolicy.maxQuestionEvidenceRecords must be a positive integer`);
  }
  if (fixture.minimumSemanticPassRate !== undefined
      && (typeof fixture.minimumSemanticPassRate !== "number"
        || fixture.minimumSemanticPassRate < 0 || fixture.minimumSemanticPassRate > 1)) {
    throw new Error(`${source}: minimumSemanticPassRate must be between 0 and 1`);
  }
  const evidenceIds = new Set();
  const runIds = new Set();
  fixture.turns.forEach((turn, index) => {
    if (typeof turn.runId !== "string" || runIds.has(turn.runId)) {
      throw new Error(`${source}: turn ${index + 1} has missing or duplicate runId`);
    }
    runIds.add(turn.runId);
    const sourceResultId = turn.sourceResultId ?? turn.answer?.resultId;
    if (typeof sourceResultId !== "string"
        || !fixture.context.investigation_results.some(result => result.result_id === sourceResultId)
        || typeof turn.sourceQuestion !== "string" || typeof turn.answer?.text !== "string") {
      throw new Error(`${source}: turn ${index + 1} must include an in-scope sourceResultId, sourceQuestion, and answer text`);
    }
    if (typeof turn.answer.evidenceId !== "string" || evidenceIds.has(turn.answer.evidenceId)) {
      throw new Error(`${source}: turn ${index + 1} has missing or duplicate evidenceId`);
    }
    evidenceIds.add(turn.answer.evidenceId);
    if (!turn.expected || !["ASK_QUESTION", "PROPOSE_COMPLETION"].includes(turn.expected.action)) {
      throw new Error(`${source}: turn ${index + 1} has no expected action`);
    }
  });
}

export function evaluateSubmission({ fixture, turnIndex, context, submission, previousQuestions = [] }) {
  const turnNumber = turnIndex + 1;
  const expected = fixture.turns[turnIndex]?.expected ?? {};
  const sourceAnswer = fixture.turns[turnIndex]?.answer ?? {};
  const action = submission?.nextAction;
  const violations = [];
  const qualityChecks = [];
  const resultIds = new Set(context.investigation_results.map(result => result.result_id));
  const evidenceIds = new Set([
    ...(context.conversation_state?.compacted_thread_memory ?? []).map(record => record.evidence_id).filter(Boolean),
    ...context.questions_and_evidence.map(record => record.evidence_id).filter(Boolean),
    ...context.investigation_results.flatMap(result => (result.current_outcomes ?? []).flatMap(outcome => outcome.evidence_ids ?? [])),
  ]);
  const addViolation = (rule, message) => violations.push({
    turn: turnNumber,
    rule,
    message,
    sourceAnswer: sourceAnswer.text,
    offendingAction: action ?? null,
  });
  const grade = (rule, passed, detail) => qualityChecks.push({ rule, passed, detail });

  if (!submission || typeof submission !== "object" || Array.isArray(submission)) {
    addViolation("submission-object", "Runtime Turn submission must be an object");
  } else {
    if (submission.runId !== context.run_id) addViolation("run-identity", `Expected runId ${context.run_id}`);
    if (submission.expectedRevision !== context.expected_revision) {
      addViolation("expected-revision", `Expected revision ${context.expected_revision}, received ${submission.expectedRevision}`);
    }
    if (!Array.isArray(submission.outcomes)) addViolation("outcomes-array", "outcomes must be an array");
    for (const [index, outcome] of (Array.isArray(submission.outcomes) ? submission.outcomes : []).entries()) {
      if (!resultIds.has(outcome?.resultId)) addViolation("outcome-result-scope", `Outcome ${index + 1} used an unknown resultId`);
      if (!COVERAGES.has(outcome?.coverage)) addViolation("outcome-coverage", `Outcome ${index + 1} used invalid coverage`);
      if ((outcome?.coverage === "SUPPORTED" || outcome?.coverage === "ASSUMPTION")
          && !CATEGORIES.has(outcome?.category ?? (outcome.coverage === "ASSUMPTION" ? "ASSUMPTION" : "FACT"))) {
        addViolation("outcome-category", `Outcome ${index + 1} used an invalid category`);
      }
      if (typeof outcome?.summary !== "string" || outcome.summary.trim() === "" || outcome.summary.length > 2_000) {
        addViolation("outcome-summary", `Outcome ${index + 1} has no valid summary`);
      }
      if (!Array.isArray(outcome?.evidenceIds) || outcome.evidenceIds.length === 0
          || outcome.evidenceIds.some(id => !evidenceIds.has(id))) {
        addViolation("outcome-evidence-scope", `Outcome ${index + 1} did not link only supplied Session Evidence`);
      }
      const fabricated = matchingTerms(outcome?.summary, fixture.assertions?.forbiddenClaims);
      if (fabricated.length > 0) addViolation("fabricated-fact", `Outcome introduced unsupported specifics: ${fabricated.join(", ")}`);
    }

    if (!action || !["ASK_QUESTION", "PROPOSE_COMPLETION"].includes(action.type)) {
      addViolation("exactly-one-next-action", "A Runtime Turn must contain one supported next action");
    } else if (action.type === "ASK_QUESTION") {
      const projectedCoverage = projectCoverage(context, submission.outcomes ?? []);
      evaluateQuestion(action, {
        context,
        projectedCoverage,
        previousQuestions,
        expected,
        assertions: fixture.assertions ?? {},
        addViolation,
        grade,
      });
      const transition = calculateThreadTransition(context, submission.outcomes ?? [], action, projectedCoverage);
      if (transition && transition.evidenceIds.length === 0) {
        addViolation("thread-transition-evidence", `Transition from ${transition.fromResultId} has no durable supporting Evidence`);
      }
    } else {
      const projected = projectCoverage(context, submission.outcomes ?? []);
      const incomplete = context.investigation_results.filter(result => result.required && OPEN_COVERAGES.has(projected.get(result.result_id)));
      if (incomplete.length > 0) addViolation("premature-completion", `Required results remain open: ${incomplete.map(value => value.result_id).join(", ")}`);
    }
    if (expected.action && action?.type !== expected.action) {
      addViolation("expected-action", `Scenario expected ${expected.action}, received ${action?.type ?? "none"}`);
    }
  }

  gradeExpectedOutcomes(context, submission?.outcomes ?? [], expected.outcomes ?? [], grade);
  gradeForbiddenOutcomes(submission?.outcomes ?? [], expected.forbiddenOutcomes ?? [], grade);
  gradeExpectedCoverage(context, submission?.outcomes ?? [], expected, grade);
  const coverageChanges = calculateCoverageChanges(context, submission?.outcomes ?? []);
  const threadTransition = action?.type === "ASK_QUESTION"
    ? calculateThreadTransition(context, submission?.outcomes ?? [], action, projectCoverage(context, submission?.outcomes ?? []))
    : null;
  const repeatedQuestion = action?.type === "ASK_QUESTION"
    && previousQuestions.some(question => questionsSemanticallyRepeat(question, action.text));
  const outcomeProvenance = (submission?.outcomes ?? []).map(outcome => ({
    resultId: outcome.resultId,
    coverage: outcome.coverage,
    category: outcome.category ?? null,
    summary: outcome.summary,
    evidenceIds: outcome.evidenceIds ?? [],
  }));
  const semanticallySupportedOutcomes = (submission?.outcomes ?? []).filter(outcome =>
    outcomeSemanticallySupported(outcome, expected, fixture.assertions ?? {}, resultIds, evidenceIds));

  return {
    contractViolations: violations,
    semanticQuality: {
      passed: qualityChecks.filter(check => check.passed).length,
      total: qualityChecks.length,
      checks: qualityChecks,
    },
    coverageChanges,
    semanticallySupportedOutcomes,
    trace: {
      turn: turnNumber,
      runId: context.run_id,
      expectedRevision: context.expected_revision,
      submittedRevision: submission?.expectedRevision ?? null,
      sourceQuestion: fixture.turns[turnIndex]?.sourceQuestion,
      sourceAnswer,
      expectedAction: expected.action ?? null,
      stressor: fixture.turns[turnIndex]?.stressor ?? null,
      action: action ?? null,
      threadTransition,
      outcomeProvenance,
      repeatedQuestion,
    },
  };
}

function outcomeSemanticallySupported(outcome, expected, assertions, resultIds, evidenceIds) {
  if (!resultIds.has(outcome?.resultId) || !Array.isArray(outcome?.evidenceIds)
      || outcome.evidenceIds.length === 0 || outcome.evidenceIds.some(id => !evidenceIds.has(id))
      || matchingTerms(outcome.summary, assertions.forbiddenClaims).length > 0) return false;
  if ((expected.forbiddenOutcomes ?? []).some(unwanted => outcome.resultId === unwanted.resultId
      && outcome.coverage === unwanted.coverage && (!unwanted.category || outcome.category === unwanted.category))) return false;
  return (expected.outcomes ?? []).some(wanted => {
    const actualCategory = outcome.coverage === "ASSUMPTION" ? "ASSUMPTION" : outcome.category ?? "FACT";
    if (outcome.resultId !== wanted.resultId || outcome.coverage !== wanted.coverage
        || (wanted.category && actualCategory !== wanted.category)) return false;
    if (wanted.evidenceIds?.some(id => !outcome.evidenceIds.includes(id))) return false;
    const summary = String(outcome.summary ?? "").toLowerCase();
    if (wanted.summaryIncludes?.length && !wanted.summaryIncludes.some(term => summary.includes(term.toLowerCase()))) return false;
    if (wanted.summaryIncludesAll?.some(term => !summary.includes(term.toLowerCase()))) return false;
    if (wanted.summaryAnchorGroups?.some(group => !group.some(term => summary.includes(term.toLowerCase())))) return false;
    return true;
  });
}

function evaluateQuestion(action, { context, projectedCoverage, previousQuestions, expected, assertions, addViolation, grade }) {
  if (!context.investigation_results.some(result => result.result_id === action.resultId)) {
    addViolation("question-result-scope", "Question used a result outside this Interview Session");
  }
  const currentThread = context.conversation_state?.current_thread_result_id;
  const currentCoverage = context.investigation_results.find(result => result.result_id === currentThread)?.coverage;
  if (currentThread && OPEN_COVERAGES.has(projectedCoverage.get(currentThread)) && action.resultId !== currentThread) {
    addViolation("current-thread", `Question left open thread ${currentThread}`);
  }
  if (currentThread === action.resultId && OPEN_COVERAGES.has(currentCoverage)
      && !OPEN_COVERAGES.has(projectedCoverage.get(currentThread))) {
    addViolation("terminal-thread-continued", `Question continued thread ${currentThread} after making it terminal`);
  }
  if (currentThread !== action.resultId && !OPEN_COVERAGES.has(projectedCoverage.get(action.resultId))) {
    addViolation("finished-thread-reopened", `Question reopened terminal thread ${action.resultId}`);
  }
  const target = context.investigation_results.find(result => result.result_id === action.resultId);
  const openRequired = context.investigation_results.filter(result => result.required
    && OPEN_COVERAGES.has(projectedCoverage.get(result.result_id)));
  if (target && !target.required && action.resultId !== currentThread && openRequired.length > 0) {
    addViolation("required-result-priority", `Optional result ${action.resultId} was chosen while required results remain open: ${openRequired.map(result => result.result_id).join(", ")}`);
  }
  if (typeof action.text !== "string" || action.text.trim() === "" || action.text.length > 2_000) {
    addViolation("question-text", "Question text must contain 1 to 2,000 characters");
    return;
  }
  const questionCount = (action.text.match(/\?/g) ?? []).length;
  if (questionCount !== 1) addViolation("one-question", `Question contained ${questionCount} question marks`);
  const exactRepeat = previousQuestions.some(question => normaliseQuestion(question) === normaliseQuestion(action.text));
  if (exactRepeat) addViolation("repeated-question", "Question repeats an earlier Question");
  if (!exactRepeat && previousQuestions.some(question => questionsSemanticallyRepeat(question, action.text))) {
    addViolation("semantically-repeated-question", "Question repeats an earlier information need in different wording");
  }
  const planningTerms = matchingTerms(action.text, assertions.forbiddenQuestionTerms);
  if (planningTerms.length > 0) {
    addViolation("mission-language-leak", `Question exposed private Mission language: ${planningTerms.join(", ")}`);
  }
  const fabricatedTerms = matchingTerms(action.text, assertions.forbiddenClaims);
  if (fabricatedTerms.length > 0) {
    addViolation("fabricated-fact", `Question introduced unsupported specifics: ${fabricatedTerms.join(", ")}`);
  }
  const unsupported = unsupportedSpecifics(action.text, context);
  if (unsupported.length > 0) {
    addViolation("unsupported-specific", `Question introduced names or numeric facts absent from supplied context: ${unsupported.join(", ")}`);
  }
  const mode = action.responseMode ?? "FREE_TEXT";
  if (!RESPONSE_MODES.has(mode)) addViolation("response-mode", `Unsupported response mode ${mode}`);
  if (mode === "CHOICE") {
    if (!Array.isArray(action.options) || action.options.length < 2 || action.options.length > 5) {
      addViolation("choice-options", "CHOICE requires two to five options");
    } else if (new Set(action.options.map(option => option.id)).size !== action.options.length
        || action.options.some(option => !option.id || !option.label)) {
      addViolation("choice-options", "CHOICE option identities and labels must be non-empty and unique");
    } else {
      const groundedWords = groundedVocabulary(context);
      const ungrounded = action.options.filter(option => {
        const words = contentWords(option.label);
        return words.length > 0 && !words.some(word => groundedWords.has(word));
      });
      if (ungrounded.length > 0) {
        addViolation("ungrounded-choice", `Choice labels were not grounded in supplied Mission or interviewee wording: ${ungrounded.map(option => option.label).join(", ")}`);
      }
    }
  }
  if (expected.resultId && action.resultId !== expected.resultId) {
    addViolation("expected-result", `Scenario expected Question resultId ${expected.resultId}`);
  }

  const semantic = expected.semantic ?? {};
  const lower = action.text.toLowerCase();
  if (semantic.vocabularyAnchors?.length) {
    const matched = semantic.vocabularyAnchors.filter(anchor => lower.includes(anchor.toLowerCase()));
    grade("interviewee-vocabulary", matched.length > 0,
      matched.length > 0 ? `Used: ${matched.join(", ")}` : `Expected one of: ${semantic.vocabularyAnchors.join(", ")}`);
  }
  if (semantic.forbiddenTerms?.length) {
    const exposed = semantic.forbiddenTerms.filter(term => lower.includes(term.toLowerCase()));
    grade("mission-language-private", exposed.length === 0,
      exposed.length === 0 ? "No private planning terms exposed" : `Exposed: ${exposed.join(", ")}`);
  }
  if (semantic.ownWords) grade("own-words-response", mode === "FREE_TEXT", `Response mode: ${mode}`);
  if (semantic.focusAnchors?.length) {
    const matched = semantic.focusAnchors.filter(anchor => lower.includes(anchor.toLowerCase()));
    grade("useful-follow-up-focus", matched.length > 0,
      matched.length > 0 ? `Focused on: ${matched.join(", ")}` : `Expected one of: ${semantic.focusAnchors.join(", ")}`);
  }
  if (semantic.pressureTerms?.length) {
    const exposed = semantic.pressureTerms.filter(term => lower.includes(term.toLowerCase()));
    grade("no-pressure-or-recruitment", exposed.length === 0,
      exposed.length === 0 ? "No pressure or owner-recruitment language" : `Exposed: ${exposed.join(", ")}`);
  }
  const bundled = /\b(?:and|or)\s+(?:what|who|when|where|why|how|does|do|is|are|should|would|can)\b/i.test(action.text);
  grade("one-information-need", !bundled, bundled ? "Question contains a second interrogative frame" : "No second interrogative frame detected");
  const latestAnswer = String(context.conversation_state?.latest_interviewee_wording ?? "").toLowerCase();
  const inventedAlternative = ["rather than", "instead of"].some(frame => lower.includes(frame) && !latestAnswer.includes(frame));
  grade("non-leading-open-question", !inventedAlternative,
    inventedAlternative ? "Question invented an alternative frame" : "No invented alternative frame detected");
  const maxLength = semantic.maxQuestionLength ?? 280;
  grade("concise-question", action.text.length <= maxLength, `${action.text.length}/${maxLength} characters`);
}

function gradeExpectedCoverage(context, outcomes, expected, grade) {
  const projected = projectCoverage(context, outcomes);
  for (const resultId of expected.openResultIds ?? []) {
    const coverage = projected.get(resultId);
    grade(`expected-open:${resultId}`, OPEN_COVERAGES.has(coverage),
      OPEN_COVERAGES.has(coverage) ? `${resultId} remains ${coverage}` : `${resultId} became ${coverage}`);
  }
}

function gradeExpectedOutcomes(context, actual, expected, grade) {
  const cumulative = context.investigation_results.flatMap(result => (result.current_outcomes ?? []).map(outcome => ({
    resultId: result.result_id,
    coverage: outcome.coverage,
    category: outcome.category,
    evidenceIds: outcome.evidence_ids ?? [],
    summary: outcome.summary,
  }))).concat(actual);
  for (const wanted of expected) {
    const matching = cumulative.filter(outcome => outcome.resultId === wanted.resultId
      && outcome.coverage === wanted.coverage
      && (!wanted.category || outcome.category === wanted.category));
    const present = matching.length > 0;
    grade(`expected-outcome:${wanted.coverage}`, present,
      present ? "Expected outcome present" : `Missing ${wanted.coverage} outcome for ${wanted.resultId}`);
    if (present && wanted.evidenceIds?.length) {
      const linkedIds = new Set(matching.flatMap(outcome => outcome.evidenceIds ?? []));
      const linked = wanted.evidenceIds.every(id => linkedIds.has(id));
      grade(`expected-provenance:${wanted.coverage}`, linked,
        linked ? "Expected Evidence linked" : `Missing Evidence: ${wanted.evidenceIds.filter(id => !linkedIds.has(id)).join(", ")}`);
    }
    if (present && wanted.summaryIncludes?.length) {
      const found = wanted.summaryIncludes.some(term => matching.some(outcome => outcome.summary?.toLowerCase().includes(term.toLowerCase())));
      grade(`expected-summary:${wanted.coverage}`, found,
        found ? "Summary contains expected domain language" : `Expected one of: ${wanted.summaryIncludes.join(", ")}`);
    }
    if (present && wanted.summaryIncludesAll?.length) {
      const missing = wanted.summaryIncludesAll.filter(term => !matching.some(outcome => outcome.summary?.toLowerCase().includes(term.toLowerCase())));
      grade(`expected-summary-all:${wanted.coverage}`, missing.length === 0,
        missing.length === 0 ? "Summary contains every evidence-critical qualifier" : `Missing: ${missing.join(", ")}`);
    }
    if (present && wanted.summaryAnchorGroups?.length) {
      const missing = wanted.summaryAnchorGroups.filter(group => !group.some(term => matching
        .some(outcome => outcome.summary?.toLowerCase().includes(term.toLowerCase()))));
      grade(`expected-summary-groups:${wanted.coverage}`, missing.length === 0,
        missing.length === 0
          ? "Summary represents every evidence-critical concept"
          : `Missing one of each group: ${missing.map(group => group.join(" | ")).join(", ")}`);
    }
  }
}

function gradeForbiddenOutcomes(actual, forbidden, grade) {
  for (const unwanted of forbidden) {
    const present = actual.some(outcome => outcome.resultId === unwanted.resultId
      && outcome.coverage === unwanted.coverage
      && (!unwanted.category || outcome.category === unwanted.category));
    grade(`forbidden-outcome:${unwanted.coverage}`, !present,
      present
        ? `Unexpected ${unwanted.coverage} outcome for ${unwanted.resultId}`
        : `No ${unwanted.coverage} outcome proposed for ${unwanted.resultId}`);
  }
}

export function applySubmission(context, submission) {
  const updated = structuredClone(context);
  for (const outcome of submission.outcomes ?? []) {
    const result = updated.investigation_results.find(candidate => candidate.result_id === outcome.resultId);
    if (!result) continue;
    if (!new Set(["SUPPORTED", "ASSUMPTION", "CONFLICT"]).has(result.coverage)
        || outcome.coverage === "CONFLICT") result.coverage = outcome.coverage;
    result.current_outcomes ??= [];
    const existing = result.current_outcomes.find(candidate => candidate.coverage === outcome.coverage
      && (candidate.category ?? null) === (outcome.category ?? null) && candidate.summary === outcome.summary);
    if (existing) {
      existing.evidence_ids = [...new Set([...(existing.evidence_ids ?? []), ...(outcome.evidenceIds ?? [])])];
    } else {
      result.current_outcomes.push({
        coverage: outcome.coverage,
        category: outcome.category ?? null,
        summary: outcome.summary,
        evidence_ids: [...(outcome.evidenceIds ?? [])],
      });
    }
  }
  return updated;
}

function projectCoverage(context, outcomes) {
  const projected = new Map(context.investigation_results.map(result => [result.result_id, result.coverage]));
  for (const outcome of outcomes) {
    const current = projected.get(outcome.resultId);
    if (current && COVERAGES.has(outcome.coverage)
        && (!new Set(["SUPPORTED", "ASSUMPTION", "CONFLICT"]).has(current)
          || outcome.coverage === "CONFLICT")) {
      projected.set(outcome.resultId, outcome.coverage);
    }
  }
  return projected;
}

function calculateThreadTransition(context, outcomes, action, projectedCoverage) {
  const fromResultId = context.conversation_state?.current_thread_result_id;
  if (!fromResultId || fromResultId === action.resultId) return null;
  const reason = projectedCoverage.get(fromResultId) ?? null;
  const submittedEvidence = outcomes
    .filter(outcome => outcome.resultId === fromResultId && outcome.coverage === reason)
    .flatMap(outcome => outcome.evidenceIds ?? []);
  const durableEvidence = context.investigation_results
    .find(result => result.result_id === fromResultId)?.current_outcomes
    ?.filter(outcome => outcome.coverage === reason)
    .flatMap(outcome => outcome.evidence_ids ?? []) ?? [];
  return {
    fromResultId,
    toResultId: action.resultId,
    reason,
    evidenceIds: [...new Set([...submittedEvidence, ...durableEvidence])],
  };
}

function calculateCoverageChanges(context, outcomes) {
  const projected = projectCoverage(context, outcomes);
  return context.investigation_results.flatMap(result => {
    const next = projected.get(result.result_id);
    if (next === result.coverage) return [];
    const evidence = outcomes.filter(outcome => outcome.resultId === result.result_id && outcome.coverage === next)
      .flatMap(outcome => outcome.evidenceIds ?? []);
    return [{ resultId: result.result_id, from: result.coverage, to: next, evidenceIds: [...new Set(evidence)] }];
  });
}

export async function runScenario({ fixture, repeat, executeRuntime }) {
  validateFixture(fixture);
  let context = structuredClone(fixture.context);
  let metricContext = structuredClone(fixture.context);
  const turns = [];
  const previousQuestions = [];
  const usages = [];
  const acceptedEvidenceIds = new Set(context.questions_and_evidence.map(record => record.evidence_id).filter(Boolean));
  const coverageProgress = [];
  let durationMs = 0;
  let previousQuestionAction;
  let recordsDropped = 0;
  let usefulAnswers = 0;

  for (let turnIndex = 0; turnIndex < fixture.turns.length; turnIndex++) {
    const fixtureTurn = fixture.turns[turnIndex];
    context.run_id = fixtureTurn.runId;
    const sourceQuestion = previousQuestionAction?.text ?? fixtureTurn.sourceQuestion;
    const sourceResultId = previousQuestionAction?.resultId
      ?? fixtureTurn.sourceResultId
      ?? fixtureTurn.answer.resultId;
    const evidenceRecord = {
      question_id: fixtureTurn.answer.questionId ?? randomUUID(),
      sequence: context.questions_and_evidence.length + 1,
      question: sourceQuestion,
      active: false,
      evidence_id: fixtureTurn.answer.evidenceId,
      result_id: sourceResultId,
      source_type: fixtureTurn.answer.sourceType,
      exact_text: fixtureTurn.answer.text,
    };
    context.questions_and_evidence.push(evidenceRecord);
    metricContext.questions_and_evidence.push(structuredClone(evidenceRecord));
    acceptedEvidenceIds.add(fixtureTurn.answer.evidenceId);
    context.conversation_state = {
      ...context.conversation_state,
      current_thread_result_id: sourceResultId,
      last_question: sourceQuestion,
      latest_interviewee_wording: fixtureTurn.answer.text,
    };
    metricContext.conversation_state = structuredClone(context.conversation_state);
    const compactedEvidenceIds = compactQuestionEvidence(context, fixture.contextPolicy?.maxQuestionEvidenceRecords);
    compactQuestionEvidence(metricContext, fixture.contextPolicy?.maxQuestionEvidenceRecords);
    recordsDropped += compactedEvidenceIds.length;

    const effectiveFixture = structuredClone(fixture);
    effectiveFixture.turns[turnIndex].sourceQuestion = sourceQuestion;
    let executed;
    try {
      executed = await executeRuntime(structuredClone(context));
    } catch (error) {
      const evaluation = evaluateSubmission({
        fixture: effectiveFixture,
        turnIndex,
        context,
        submission: null,
        previousQuestions,
      });
      evaluation.contractViolations.unshift({
        turn: turnIndex + 1,
        rule: "runtime-execution",
        message: error.message,
        sourceAnswer: fixtureTurn.answer.text,
        offendingAction: null,
      });
      evaluation.durationMs = null;
      evaluation.usage = null;
      evaluation.runtimeEvent = null;
      evaluation.executionError = error.message;
      evaluation.trace.compactedEvidenceIds = compactedEvidenceIds;
      evaluation.trace.contextEvidenceRecords = context.questions_and_evidence.length;
      turns.push(evaluation);
      break;
    }
    durationMs += executed.durationMs ?? 0;
    if (executed.usage) usages.push(executed.usage);
    const evaluation = evaluateSubmission({
      fixture: effectiveFixture,
      turnIndex,
      context,
      submission: executed.submission,
      previousQuestions,
    });
    evaluation.durationMs = executed.durationMs ?? null;
    evaluation.interviewerDurationMs = executed.interviewerDurationMs ?? executed.durationMs ?? null;
    evaluation.reviewerDurationMs = executed.reviewerDurationMs ?? null;
    evaluation.usage = executed.usage ?? null;
    evaluation.interviewerUsage = executed.interviewerUsage ?? executed.usage ?? null;
    evaluation.reviewerUsage = executed.reviewerUsage ?? null;
    evaluation.runtimeEvent = executed.event ?? null;
    evaluation.trace.submissionRejections = (executed.rejectedSubmissions ?? []).map(rejection => ({
      code: rejection.code,
      submission: rejection.submission,
    }));
    for (const rejection of executed.rejectedSubmissions ?? []) {
      evaluation.contractViolations.push({
        turn: turnIndex + 1,
        rule: `runtime-rejected:${rejection.code}`,
        message: `Production Runtime Turn contract rejected a proposal with ${rejection.code}`,
        sourceAnswer: fixtureTurn.answer.text,
        offendingAction: rejection.submission?.nextAction ?? null,
      });
    }
    evaluation.trace.review = executed.review ?? null;
    if (executed.review?.accepted === false) {
      evaluation.contractViolations.push({
        turn: turnIndex + 1,
        rule: "reviewer-proposal-rejected",
        message: executed.review.rejection ?? "Reviewer proposal was rejected",
        sourceAnswer: fixtureTurn.answer.text,
        offendingAction: null,
      });
    }
    evaluation.trace.compactedEvidenceIds = compactedEvidenceIds;
    evaluation.trace.contextEvidenceRecords = context.questions_and_evidence.length;
    turns.push(evaluation);

    const linkedCurrentAnswer = evaluation.semanticallySupportedOutcomes
      .some(outcome => outcome.evidenceIds?.includes(fixtureTurn.answer.evidenceId));
    if (linkedCurrentAnswer) usefulAnswers += 1;
    context = executed.authoritativeContext ?? applySubmission(context, executed.submission ?? {});
    if (!executed.authoritativeContext) context.expected_revision += 1;
    metricContext = applyAcceptedRuntimeOutcomes(metricContext, evaluation.semanticallySupportedOutcomes);
    metricContext.expected_revision += 1;
    const required = metricContext.investigation_results.filter(result => result.required);
    const terminal = required.filter(result => TERMINAL_COVERAGES.has(result.coverage)).length;
    coverageProgress.push({ turn: turnIndex + 1, terminal, total: required.length, completionRate: rate(terminal, required.length) });
    if (executed.submission?.nextAction?.type === "ASK_QUESTION") {
      previousQuestionAction = executed.submission.nextAction;
      previousQuestions.push(previousQuestionAction.text);
    } else {
      previousQuestionAction = undefined;
      break;
    }
  }

  const semanticPassed = turns.reduce((total, turn) => total + turn.semanticQuality.passed, 0);
  const semanticTotal = turns.reduce((total, turn) => total + turn.semanticQuality.total, 0);
  const semanticPassRate = semanticTotal === 0 ? 1 : semanticPassed / semanticTotal;
  const metrics = scenarioMetrics({
    fixture,
    context: metricContext,
    turns,
    acceptedEvidenceIds,
    coverageProgress,
    recordsDropped,
    usefulAnswers,
  });
  return {
    scenarioId: fixture.id,
    title: fixture.title,
    fixtureVersion: fixture.fixtureVersion,
    persona: fixture.persona,
    repeat,
    durationMs,
    usage: aggregateUsage(usages),
    minimumSemanticPassRate: fixture.minimumSemanticPassRate ?? null,
    semanticGatePassed: fixture.minimumSemanticPassRate === undefined
      || semanticPassRate >= fixture.minimumSemanticPassRate,
    continuityGatePassed: metrics.continuity.allInvestigatedItemsTraceable
      && metrics.compaction.criticalEvidenceRetained
      && turns.length >= (fixture.assertions?.minimumTurns ?? 1),
    metrics,
    turns,
  };
}

function compactQuestionEvidence(context, maximumRecords) {
  if (!maximumRecords || context.questions_and_evidence.length <= maximumRecords) return [];
  const removed = context.questions_and_evidence.splice(0, context.questions_and_evidence.length - maximumRecords);
  context.conversation_state ??= {};
  context.conversation_state.compacted_thread_memory = [
    ...(context.conversation_state.compacted_thread_memory ?? []),
    ...removed.filter(record => record.evidence_id).map(record => ({
      result_id: record.result_id,
      question: record.question,
      evidence_id: record.evidence_id,
    })),
  ];
  return removed.map(record => record.evidence_id).filter(Boolean);
}

function scenarioMetrics({ fixture, context, turns, acceptedEvidenceIds, coverageProgress, recordsDropped, usefulAnswers }) {
  const required = context.investigation_results.filter(result => result.required);
  const requiredTerminal = required.filter(result => TERMINAL_COVERAGES.has(result.coverage)).length;
  const retainedEvidenceIds = new Set([
    ...context.questions_and_evidence.map(record => record.evidence_id).filter(Boolean),
    ...context.investigation_results.flatMap(result => (result.current_outcomes ?? []).flatMap(outcome => outcome.evidence_ids ?? [])),
  ]);
  const criticalEvidenceIds = fixture.assertions?.criticalEvidenceIds ?? [];
  const missingCriticalEvidenceIds = criticalEvidenceIds.filter(id => !retainedEvidenceIds.has(id));
  const investigatedResults = context.investigation_results.filter(result => result.required
    || result.coverage !== "UNADDRESSED" || (result.current_outcomes ?? []).length > 0);
  const untraceableResultIds = investigatedResults.filter(result => {
    if (!TERMINAL_COVERAGES.has(result.coverage)) return true;
    const linked = new Set((result.current_outcomes ?? []).flatMap(outcome => outcome.evidence_ids ?? []));
    return linked.size === 0 || [...linked].some(id => !acceptedEvidenceIds.has(id));
  }).map(result => result.result_id);
  const durations = turns.map(turn => turn.durationMs).filter(value => typeof value === "number");
  return {
    requiredCoverage: {
      total: required.length,
      terminal: requiredTerminal,
      completionRate: rate(requiredTerminal, required.length),
    },
    requiredCoverageProgress: coverageProgress,
    usefulEvidence: {
      questions: turns.length,
      answersLinkedToOutcomes: usefulAnswers,
      perQuestion: rate(usefulAnswers, turns.length),
    },
    latencyMs: { p50: percentile(durations, 0.5), p95: percentile(durations, 0.95) },
    compaction: {
      enabled: Boolean(fixture.contextPolicy?.maxQuestionEvidenceRecords),
      recordsDropped,
      criticalEvidenceRetained: missingCriticalEvidenceIds.length === 0,
      missingCriticalEvidenceIds,
    },
    continuity: {
      allInvestigatedItemsTraceable: untraceableResultIds.length === 0,
      untraceableResultIds,
    },
    qualityByPhase: qualityByPhase(turns),
    qualityDegradation: qualityDegradation(turns),
    reviewer: reviewerMetrics(turns),
  };
}

function reviewerMetrics(turns) {
  const reviews = turns.map(turn => ({
    review: turn.trace?.review,
    durationMs: turn.reviewerDurationMs,
  })).filter(value => value.review);
  const triggers = {};
  for (const { review } of reviews) triggers[review.trigger] = (triggers[review.trigger] ?? 0) + 1;
  const durations = reviews.map(value => value.durationMs).filter(value => typeof value === "number");
  return {
    invocations: reviews.length,
    accepted: reviews.filter(value => value.review.accepted).length,
    rejected: reviews.filter(value => !value.review.accepted).length,
    cadence: rate(reviews.length, turns.length),
    triggers,
    latencyMs: { p50: percentile(durations, 0.5), p95: percentile(durations, 0.95) },
  };
}

function qualityByPhase(turns) {
  const names = ["early", "middle", "late"];
  return names.map((phase, phaseIndex) => {
    const selected = turns.filter((_, index) => Math.min(2, Math.floor(index * 3 / Math.max(1, turns.length))) === phaseIndex);
    const passed = selected.reduce((total, turn) => total + turn.semanticQuality.passed, 0);
    const total = selected.reduce((sum, turn) => sum + turn.semanticQuality.total, 0);
    return { phase, turns: selected.length, passed, total, passRate: total === 0 ? 1 : passed / total };
  });
}

function qualityDegradation(turns) {
  const phases = qualityByPhase(turns);
  const failedRules = turns.slice(Math.floor(turns.length * 2 / 3))
    .flatMap(turn => turn.semanticQuality.checks.filter(check => !check.passed).map(check => check.rule));
  return {
    lateVsEarlySemanticDelta: phases[2].passRate - phases[0].passRate,
    lateFailedRules: [...new Set(failedRules)].sort(),
  };
}

function aggregateUsage(usages) {
  if (usages.length === 0) return null;
  const result = {
    inputTokens: sumAvailable(usages, "inputTokens"),
    outputTokens: sumAvailable(usages, "outputTokens"),
    cacheWriteTokens: sumAvailable(usages, "cacheWriteTokens"),
    costUsd: sumAvailable(usages, "costUsd"),
    availableTurns: usages.length,
  };
  const identities = usages.map(usage => usage.modelIdentity).filter(Boolean);
  if (identities.length > 0) {
    const unique = new Map(identities.map(identity => [JSON.stringify(identity), identity]));
    result.modelIdentity = unique.size === 1 && identities.length === usages.length
      ? structuredClone(identities[0])
      : null;
  }
  return result;
}

function sumAvailable(values, field) {
  const available = values.map(value => value[field]).filter(value => typeof value === "number");
  return available.length === 0 ? null : available.reduce((total, value) => total + value, 0);
}

export function summariseRuns(runs) {
  const turns = runs.flatMap(run => run.turns ?? []);
  const deterministicPasses = runs.filter(run => (run.turns ?? []).every(turn => turn.contractViolations.length === 0)).length;
  const semanticPassed = turns.reduce((total, turn) => total + (turn.semanticQuality?.passed ?? 0), 0);
  const semanticTotal = turns.reduce((total, turn) => total + (turn.semanticQuality?.total ?? 0), 0);
  const coverage = runs.map(run => run.metrics?.requiredCoverage?.completionRate).filter(value => typeof value === "number");
  const evidenceYield = runs.map(run => run.metrics?.usefulEvidence?.perQuestion).filter(value => typeof value === "number");
  const durations = turns.map(turn => turn.durationMs).filter(value => typeof value === "number");
  const semanticRates = runs.map(run => {
    const passed = (run.turns ?? []).reduce((total, turn) => total + (turn.semanticQuality?.passed ?? 0), 0);
    const total = (run.turns ?? []).reduce((sum, turn) => sum + (turn.semanticQuality?.total ?? 0), 0);
    return total === 0 ? 1 : passed / total;
  });
  const turnsToSufficientEvidence = runs.map(run => run.metrics?.requiredCoverageProgress
    ?.find(progress => progress.total > 0 && progress.terminal === progress.total)?.turn)
    .filter(value => typeof value === "number");
  const costs = runs.map(run => run.usage?.costUsd).filter(value => typeof value === "number");
  const costsPerQuestion = runs.map(run => {
    const cost = run.usage?.costUsd;
    const questionCount = run.turns?.length ?? 0;
    return typeof cost === "number" && questionCount > 0 ? cost / questionCount : null;
  }).filter(value => typeof value === "number");
  const repeatedQuestions = turns.filter(turn => turn.trace?.repeatedQuestion
    || turn.contractViolations?.some(violation => ["repeated-question", "semantically-repeated-question"].includes(violation.rule))).length;
  const earlyCompletions = turns.filter(turn => turn.contractViolations
    ?.some(violation => violation.rule === "premature-completion")).length;
  const completionErrors = turns.filter(turn => turn.contractViolations
    ?.some(violation => ["premature-completion", "expected-action"].includes(violation.rule))).length;
  const contradictionsMissed = turns.reduce((total, turn) => total + (turn.semanticQuality?.checks ?? [])
    .filter(check => check.rule === "expected-outcome:CONFLICT" && !check.passed).length, 0);
  const reviewInvocations = turns.filter(turn => turn.trace?.review).length;
  const reviewDurations = turns.filter(turn => turn.trace?.review)
    .map(turn => turn.reviewerDurationMs).filter(value => typeof value === "number");
  const reviewTriggers = {};
  for (const turn of turns.filter(value => value.trace?.review)) {
    const trigger = turn.trace.review.trigger;
    reviewTriggers[trigger] = (reviewTriggers[trigger] ?? 0) + 1;
  }
  return {
    scenarios: new Set(runs.map(run => run.scenarioId)).size,
    runs: runs.length,
    turns: turns.length,
    deterministicPasses,
    deterministicFailures: runs.length - deterministicPasses,
    semanticGateFailures: runs.filter(run => run.semanticGatePassed === false).length,
    continuityGateFailures: runs.filter(run => run.continuityGatePassed === false).length,
    semanticPassed,
    semanticTotal,
    semanticPassRate: semanticTotal === 0 ? 1 : semanticPassed / semanticTotal,
    requiredCoverageCompletionRate: averageOrNull(coverage),
    usefulEvidencePerQuestion: averageOrNull(evidenceYield),
    latencyMs: { p50: percentile(durations, 0.5), p95: percentile(durations, 0.95) },
    durationMs: runs.reduce((total, run) => total + (run.durationMs ?? 0), 0),
    distributions: {
      semanticPassRate: distribution(semanticRates),
      requiredCoverageCompletionRate: distribution(coverage),
      usefulEvidencePerQuestion: distribution(evidenceYield),
      turnsToSufficientEvidence: distribution(turnsToSufficientEvidence),
      latencyMs: distribution(durations),
      costUsdPerRun: distribution(costs),
      costUsdPerQuestion: distribution(costsPerQuestion),
    },
    runsWithoutSufficientEvidence: runs.length - turnsToSufficientEvidence.length,
    failureCounts: { contradictionsMissed, repeatedQuestions, earlyCompletions, completionErrors },
    reviewer: {
      invocations: reviewInvocations,
      accepted: turns.filter(turn => turn.trace?.review?.accepted).length,
      rejected: turns.filter(turn => turn.trace?.review?.accepted === false).length,
      eligibleTurns: turns.length,
      cadence: rate(reviewInvocations, turns.length),
      triggers: reviewTriggers,
      latencyMs: distribution(reviewDurations),
    },
  };
}

export function compareWithBaseline(summary, baseline) {
  if (!baseline) return null;
  const semanticDelta = summary.semanticPassRate - baseline.semanticPassRate;
  const deterministicFailureDelta = summary.deterministicFailures - baseline.deterministicFailures;
  return {
    baseline,
    semanticDelta,
    deterministicFailureDelta,
    regressed: semanticDelta < 0 || deterministicFailureDelta > 0,
  };
}

export function formatSummary(report) {
  const { metadata, summary, comparison, runs } = report;
  const lines = [
    "# Adaptive Interview Evaluation",
    "",
    `- Commit: \`${metadata.commit}\``,
    `- Model: \`${metadata.model}\``,
    `- Reasoning effort: \`${metadata.reasoningEffort}\``,
    `- Runtime: \`${metadata.runtimeVersion ?? "unavailable"}\` from \`${metadata.runtimeImage ?? "unavailable"}\``,
    `- Fixture versions: ${metadata.fixtureVersions ? Object.entries(metadata.fixtureVersions).map(([id, version]) => `${id}@${version}`).join(", ") : "unavailable"}`,
    `- Token usage: ${metadata.tokenUsage?.available ? `${metadata.tokenUsage.inputTokens ?? "?"} input / ${metadata.tokenUsage.outputTokens ?? "?"} output / ${metadata.tokenUsage.cacheWriteTokens ?? "?"} cache-write` : "unavailable"}; cost: ${typeof metadata.costUsd === "number" ? `$${metadata.costUsd.toFixed(6)}` : "unavailable"}`,
    `- Scenarios: ${summary.scenarios}; repeat runs: ${summary.runs}; turns: ${summary.turns}`,
    `- Deterministic: ${summary.deterministicPasses}/${summary.runs} runs passed`,
    `- Required semantic gates: ${summary.semanticGateFailures ?? 0} failed; continuity gates: ${summary.continuityGateFailures ?? 0} failed`,
    `- Semantic quality: ${summary.semanticPassed}/${summary.semanticTotal} checks passed (${(summary.semanticPassRate * 100).toFixed(1)}%)`,
    `- Required coverage: ${formatPercent(summary.requiredCoverageCompletionRate)}; useful Evidence/question: ${formatNumber(summary.usefulEvidencePerQuestion)}`,
    `- Turn latency: p50 ${formatMilliseconds(summary.latencyMs?.p50)}, p95 ${formatMilliseconds(summary.latencyMs?.p95)}`,
    `- Duration: ${summary.durationMs} ms`,
  ];
  if (comparison) lines.push(`- Baseline: semantic ${(comparison.semanticDelta * 100).toFixed(1)} points; deterministic failures ${signed(comparison.deterministicFailureDelta)}`);
  lines.push("");
  for (const run of runs) {
    const failures = (run.turns ?? []).flatMap(turn => turn.contractViolations);
    const passed = (run.turns ?? []).reduce((total, turn) => total + turn.semanticQuality.passed, 0);
    const total = (run.turns ?? []).reduce((sum, turn) => sum + turn.semanticQuality.total, 0);
    lines.push(`## ${run.scenarioId} — repeat ${run.repeat}`);
    lines.push(`- Contract: ${failures.length === 0 ? "pass" : `${failures.length} violation(s)`}`);
    lines.push(`- Semantic: ${passed}/${total}${run.minimumSemanticPassRate === null || run.minimumSemanticPassRate === undefined
      ? ""
      : `; required ${(run.minimumSemanticPassRate * 100).toFixed(1)}% — ${run.semanticGatePassed ? "pass" : "fail"}`}`);
    if (run.metrics) {
      lines.push(`- Continuity: ${run.continuityGatePassed ? "pass" : "fail"}; required coverage ${formatPercent(run.metrics.requiredCoverage.completionRate)}; useful Evidence/question ${formatNumber(run.metrics.usefulEvidence.perQuestion)}`);
      lines.push(`- Latency: p50 ${formatMilliseconds(run.metrics.latencyMs.p50)}, p95 ${formatMilliseconds(run.metrics.latencyMs.p95)}; compacted records ${run.metrics.compaction.recordsDropped}`);
      lines.push(`- Context-growth quality: early/middle/late ${run.metrics.qualityByPhase.map(phase => `${phase.phase} ${(phase.passRate * 100).toFixed(1)}%`).join(", ")}; late-vs-early ${(run.metrics.qualityDegradation.lateVsEarlySemanticDelta * 100).toFixed(1)} points`);
      if (run.metrics.qualityDegradation.lateFailedRules.length > 0) lines.push(`- Late quality failures: ${run.metrics.qualityDegradation.lateFailedRules.join(", ")}`);
    }
    for (const failure of failures) {
      lines.push(`- **Turn ${failure.turn}, ${failure.rule}:** ${failure.message}`);
      lines.push(`  - Source answer: ${failure.sourceAnswer}`);
      lines.push(`  - Offending action: \`${JSON.stringify(failure.offendingAction)}\``);
    }
    for (const turn of run.turns ?? []) {
      for (const check of turn.semanticQuality.checks?.filter(value => !value.passed) ?? []) {
        lines.push(`- **Turn ${turn.trace.turn}, semantic ${check.rule}:** ${check.detail}`);
      }
    }
    lines.push("");
  }
  return `${lines.join("\n")}\n`;
}

export function normaliseQuestion(value) {
  return String(value ?? "").toLowerCase().replace(/[^a-z0-9 ]/g, "").replace(/\s+/g, " ").trim();
}

function questionsSemanticallyRepeat(first, second) {
  if (normaliseQuestion(first) === normaliseQuestion(second)) return true;
  const firstWords = new Set(contentWords(first));
  const secondWords = new Set(contentWords(second));
  if (firstWords.size < 3 || secondWords.size < 3) return false;
  const intersection = [...firstWords].filter(word => secondWords.has(word)).length;
  const overlap = intersection / Math.min(firstWords.size, secondWords.size);
  const union = new Set([...firstWords, ...secondWords]).size;
  return overlap >= 0.72 || intersection / union >= 0.55;
}

function unsupportedSpecifics(value, context) {
  const grounded = [
    ...Object.values(context.mission ?? {}),
    ...(context.shared_mission_entries ?? []).flatMap(entry => [entry.value, entry.detail]),
    ...(context.questions_and_evidence ?? []).flatMap(record => [record.question, record.exact_text, record.option_label, record.explanation]),
    ...(context.investigation_results ?? []).flatMap(result => (result.current_outcomes ?? []).map(outcome => outcome.summary)),
  ].join(" ").toLowerCase();
  const input = String(value ?? "");
  const numeric = input.match(/(?:£|\$|€)?\d[\d,.]*/g) ?? [];
  const names = [...input.matchAll(/\b[A-Z][a-z]{2,}(?:'[a-z]+)?\b/g)]
    .map(match => match[0])
    .filter(word => {
      const lower = word.toLowerCase();
      return !QUESTION_STOP_WORDS.has(lower) && !QUESTION_STOP_WORDS.has(stemWord(lower));
    });
  return [...new Set([...numeric, ...names].filter(specific => !grounded.includes(specific.toLowerCase())))];
}

function groundedVocabulary(context) {
  const values = [
    ...Object.values(context.mission ?? {}),
    ...(context.shared_mission_entries ?? []).flatMap(entry => [entry.value, entry.detail]),
    ...(context.questions_and_evidence ?? []).flatMap(record => [record.question, record.exact_text, record.option_label, record.explanation]),
    ...(context.investigation_results ?? []).flatMap(result => [
      result.knowledge_gap,
      result.why_it_matters,
      result.relevant_context,
      result.sufficient_evidence,
      ...(result.current_outcomes ?? []).map(outcome => outcome.summary),
    ]),
  ];
  return new Set(values.flatMap(contentWords));
}

function contentWords(value) {
  return String(value ?? "").toLowerCase().match(/[a-z0-9]+/g)?.map(stemWord)
    .filter(word => word.length > 1 && !QUESTION_STOP_WORDS.has(word)) ?? [];
}

function stemWord(word) {
  if (word.endsWith("ies") && word.length > 4) return `${word.slice(0, -3)}y`;
  if (word.endsWith("ing") && word.length > 5) return word.slice(0, -3);
  if (word.endsWith("ed") && word.length > 4) return word.slice(0, -2);
  if (word.endsWith("es") && word.length > 4) return word.slice(0, -2);
  if (word.endsWith("s") && word.length > 3) return word.slice(0, -1);
  return word;
}

function matchingTerms(value, terms = []) {
  const lower = String(value ?? "").toLowerCase();
  return terms.filter(term => lower.includes(String(term).toLowerCase()));
}

function rate(numerator, denominator) {
  return denominator === 0 ? 1 : numerator / denominator;
}

function averageOrNull(values) {
  return values.length === 0 ? null : values.reduce((total, value) => total + value, 0) / values.length;
}

function distribution(values) {
  if (values.length === 0) {
    return { count: 0, min: null, p25: null, p50: null, p75: null, p95: null, max: null, mean: null };
  }
  return {
    count: values.length,
    min: Math.min(...values),
    p25: percentile(values, 0.25),
    p50: percentile(values, 0.5),
    p75: percentile(values, 0.75),
    p95: percentile(values, 0.95),
    max: Math.max(...values),
    mean: averageOrNull(values),
  };
}

function percentile(values, quantile) {
  if (values.length === 0) return null;
  const ordered = [...values].sort((a, b) => a - b);
  const position = (ordered.length - 1) * quantile;
  const lower = Math.floor(position);
  const upper = Math.ceil(position);
  if (lower === upper) return ordered[lower];
  return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower);
}

function formatPercent(value) {
  return typeof value === "number" ? `${(value * 100).toFixed(1)}%` : "unavailable";
}

function formatNumber(value) {
  return typeof value === "number" ? value.toFixed(3) : "unavailable";
}

function formatMilliseconds(value) {
  return typeof value === "number" ? `${value.toFixed(1)} ms` : "unavailable";
}

function signed(value) {
  return value > 0 ? `+${value}` : String(value);
}
