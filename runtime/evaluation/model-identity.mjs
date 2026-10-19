const MODEL_IDENTITY_FIELDS = ["provider", "requestedModel", "responseModel", "reasoningEffort"];

export function validateModelIdentityObservations(identities, expected, label, expectedLabel = "configured") {
  if (identities.length === 0 || identities.some(identity => !identity)) {
    throw new Error(`${label} model identity was not captured for every successful ${label}`);
  }
  if (identities.some(identity => typeof identity !== "object" || MODEL_IDENTITY_FIELDS
    .some(field => typeof identity[field] !== "string" || identity[field] === ""))) {
    throw new Error(`${label} model identity is incomplete for a successful ${label}`);
  }
  const unique = new Map(identities.map(identity => [modelIdentityKey(identity), identity]));
  if (unique.size !== 1) throw new Error(`${label}s used more than one model or reasoning identity`);
  const actual = identities[0];
  for (const field of MODEL_IDENTITY_FIELDS) {
    if (expected[field] && actual[field] !== expected[field]) {
      throw new Error(`${label} ${field} ${actual[field]} does not match ${expectedLabel} ${field} ${expected[field]}`);
    }
  }
  return structuredClone(actual);
}

export function modelIdentityKey(identity) {
  return JSON.stringify(MODEL_IDENTITY_FIELDS.map(field => identity?.[field]));
}
