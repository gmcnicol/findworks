const CORRECTABLE_CODES = new Set([
  "unsubstantiated_unresolved_outcome",
  "premature_completion",
  "invalid_outcome_evidence",
  "invalid_outcome_scope",
  "invalid_question_scope",
  "exactly_one_next_action_required",
]);

export async function submitTurn({ url, token, params, timeoutMs = 15_000 }) {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Authorization": `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify(params),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const body = await response.text();
  if (!response.ok) {
    let code;
    try { code = JSON.parse(body).code; } catch { code = undefined; }
    throw new Error(`Turn rejected: ${CORRECTABLE_CODES.has(code) ? code : `http_${response.status}`}`);
  }
  return body;
}
