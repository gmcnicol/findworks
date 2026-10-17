import { randomUUID } from "node:crypto";
import { readdir, readFile } from "node:fs/promises";
import { join } from "node:path";

const COVERAGES = new Set(["SUPPORTED", "UNKNOWN", "CONFLICT", "ASSUMPTION", "OWNERSHIP_GAP"]);
const CATEGORIES = new Set(["FACT", "RULE", "DECISION", "TERM", "EXCEPTION", "ASSUMPTION"]);
const RESPONSE_MODES = new Set(["FREE_TEXT", "YES_NO", "YES_NO_PARTLY", "PARAPHRASE", "CHOICE"]);
const OPEN_COVERAGES = new Set(["UNADDRESSED", "EXPLORING", "PARTIAL"]);

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
    if (typeof turn.sourceQuestion !== "string" || typeof turn.answer?.text !== "string") {
      throw new Error(`${source}: turn ${index + 1} must include sourceQuestion and answer text`);
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
  const evidenceIds = new Set(context.questions_and_evidence.map(record => record.evidence_id).filter(Boolean));
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
    }

    if (!action || !["ASK_QUESTION", "PROPOSE_COMPLETION"].includes(action.type)) {
      addViolation("exactly-one-next-action", "A Runtime Turn must contain one supported next action");
    } else if (action.type === "ASK_QUESTION") {
      evaluateQuestion(action, {
        context,
        projectedCoverage: projectCoverage(context, submission.outcomes ?? []),
        previousQuestions,
        expected,
        addViolation,
        grade,
      });
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
  const coverageChanges = calculateCoverageChanges(context, submission?.outcomes ?? []);
  const repeatedQuestion = action?.type === "ASK_QUESTION"
    && previousQuestions.some(question => normaliseQuestion(question) === normaliseQuestion(action.text));
  const outcomeProvenance = (submission?.outcomes ?? []).map(outcome => ({
    resultId: outcome.resultId,
    coverage: outcome.coverage,
    category: outcome.category ?? null,
    summary: outcome.summary,
    evidenceIds: outcome.evidenceIds ?? [],
  }));

  return {
    contractViolations: violations,
    semanticQuality: {
      passed: qualityChecks.filter(check => check.passed).length,
      total: qualityChecks.length,
      checks: qualityChecks,
    },
    coverageChanges,
    trace: {
      turn: turnNumber,
      runId: context.run_id,
      expectedRevision: context.expected_revision,
      submittedRevision: submission?.expectedRevision ?? null,
      sourceQuestion: fixture.turns[turnIndex]?.sourceQuestion,
      sourceAnswer,
      action: action ?? null,
      outcomeProvenance,
      repeatedQuestion,
    },
  };
}

function evaluateQuestion(action, { context, projectedCoverage, previousQuestions, expected, addViolation, grade }) {
  if (!context.investigation_results.some(result => result.result_id === action.resultId)) {
    addViolation("question-result-scope", "Question used a result outside this Interview Session");
  }
  const currentThread = context.conversation_state?.current_thread_result_id;
  if (currentThread && OPEN_COVERAGES.has(projectedCoverage.get(currentThread)) && action.resultId !== currentThread) {
    addViolation("current-thread", `Question left open thread ${currentThread}`);
  }
  if (typeof action.text !== "string" || action.text.trim() === "" || action.text.length > 2_000) {
    addViolation("question-text", "Question text must contain 1 to 2,000 characters");
    return;
  }
  const questionCount = (action.text.match(/\?/g) ?? []).length;
  if (questionCount !== 1) addViolation("one-question", `Question contained ${questionCount} question marks`);
  if (previousQuestions.some(question => normaliseQuestion(question) === normaliseQuestion(action.text))) {
    addViolation("repeated-question", "Question repeats an earlier Question");
  }
  const mode = action.responseMode ?? "FREE_TEXT";
  if (!RESPONSE_MODES.has(mode)) addViolation("response-mode", `Unsupported response mode ${mode}`);
  if (mode === "CHOICE") {
    if (!Array.isArray(action.options) || action.options.length < 2 || action.options.length > 5) {
      addViolation("choice-options", "CHOICE requires two to five options");
    } else if (new Set(action.options.map(option => option.id)).size !== action.options.length
        || action.options.some(option => !option.id || !option.label)) {
      addViolation("choice-options", "CHOICE option identities and labels must be non-empty and unique");
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
  const turns = [];
  const previousQuestions = [];
  const usages = [];
  let durationMs = 0;
  let previousActionQuestion;

  for (let turnIndex = 0; turnIndex < fixture.turns.length; turnIndex++) {
    const fixtureTurn = fixture.turns[turnIndex];
    context.run_id = fixtureTurn.runId;
    const sourceQuestion = previousActionQuestion ?? fixtureTurn.sourceQuestion;
    context.questions_and_evidence.push({
      question_id: fixtureTurn.answer.questionId ?? randomUUID(),
      sequence: context.questions_and_evidence.length + 1,
      question: sourceQuestion,
      active: false,
      evidence_id: fixtureTurn.answer.evidenceId,
      source_type: fixtureTurn.answer.sourceType,
      exact_text: fixtureTurn.answer.text,
    });
    context.conversation_state = {
      ...context.conversation_state,
      current_thread_result_id: fixtureTurn.expected.resultId
        ?? context.conversation_state?.current_thread_result_id
        ?? context.investigation_results.find(result => OPEN_COVERAGES.has(result.coverage))?.result_id,
      last_question: sourceQuestion,
      latest_interviewee_wording: fixtureTurn.answer.text,
    };

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
    evaluation.usage = executed.usage ?? null;
    evaluation.runtimeEvent = executed.event ?? null;
    turns.push(evaluation);

    context = applySubmission(context, executed.submission ?? {});
    context.expected_revision += 1;
    if (executed.submission?.nextAction?.type === "ASK_QUESTION") {
      previousActionQuestion = executed.submission.nextAction.text;
      previousQuestions.push(previousActionQuestion);
    } else {
      previousActionQuestion = undefined;
    }
  }

  const semanticPassed = turns.reduce((total, turn) => total + turn.semanticQuality.passed, 0);
  const semanticTotal = turns.reduce((total, turn) => total + turn.semanticQuality.total, 0);
  const semanticPassRate = semanticTotal === 0 ? 1 : semanticPassed / semanticTotal;
  return {
    scenarioId: fixture.id,
    fixtureVersion: fixture.fixtureVersion,
    persona: fixture.persona,
    repeat,
    durationMs,
    usage: aggregateUsage(usages),
    minimumSemanticPassRate: fixture.minimumSemanticPassRate ?? null,
    semanticGatePassed: fixture.minimumSemanticPassRate === undefined
      || semanticPassRate >= fixture.minimumSemanticPassRate,
    turns,
  };
}

function aggregateUsage(usages) {
  if (usages.length === 0) return null;
  return {
    inputTokens: sumAvailable(usages, "inputTokens"),
    outputTokens: sumAvailable(usages, "outputTokens"),
    cacheWriteTokens: sumAvailable(usages, "cacheWriteTokens"),
    costUsd: sumAvailable(usages, "costUsd"),
    availableTurns: usages.length,
  };
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
  return {
    scenarios: new Set(runs.map(run => run.scenarioId)).size,
    runs: runs.length,
    turns: turns.length,
    deterministicPasses,
    deterministicFailures: runs.length - deterministicPasses,
    semanticGateFailures: runs.filter(run => run.semanticGatePassed === false).length,
    semanticPassed,
    semanticTotal,
    semanticPassRate: semanticTotal === 0 ? 1 : semanticPassed / semanticTotal,
    durationMs: runs.reduce((total, run) => total + (run.durationMs ?? 0), 0),
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
    `- Required semantic gates: ${summary.semanticGateFailures ?? 0} failed`,
    `- Semantic quality: ${summary.semanticPassed}/${summary.semanticTotal} checks passed (${(summary.semanticPassRate * 100).toFixed(1)}%)`,
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

function signed(value) {
  return value > 0 ? `+${value}` : String(value);
}
