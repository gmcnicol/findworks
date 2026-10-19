const DRAFT_STORAGE_PREFIX = "findworks:interview-drafts:v1:";
const DRAFT_TTL_MS = 24 * 60 * 60 * 1000;
const MAX_DRAFTS_PER_SESSION = 32;
const MAX_DRAFT_LENGTH = 10_000;

const draftFields = () => document.querySelectorAll("#interview-shell textarea[data-draft-key]");
const interviewSessionId = () => document.querySelector("#interview-shell")?.dataset.interviewSession;
const storageKey = (sessionId) => `${DRAFT_STORAGE_PREFIX}${sessionId}`;

const clearAllDrafts = () => {
  try {
    Object.keys(sessionStorage)
      .filter((key) => key.startsWith(DRAFT_STORAGE_PREFIX))
      .forEach((key) => sessionStorage.removeItem(key));
  } catch { /* Storage can be unavailable in privacy-restricted browsers. */ }
};

const removeLegacyDrafts = () => {
  try {
    Object.keys(localStorage)
      .filter((key) => key.startsWith("findworks:answer:") || key.startsWith("findworks:correction:"))
      .forEach((key) => localStorage.removeItem(key));
  } catch { /* Draft recovery remains an enhancement when storage is unavailable. */ }
};

const readDrafts = (sessionId) => {
  if (!sessionId) return {};
  try {
    const key = storageKey(sessionId);
    const payload = JSON.parse(sessionStorage.getItem(key) ?? "null");
    if (!payload || payload.expiresAt <= Date.now() || typeof payload.drafts !== "object") {
      sessionStorage.removeItem(key);
      return {};
    }
    return payload.drafts;
  } catch {
    try { sessionStorage.removeItem(storageKey(sessionId)); } catch { /* No-op. */ }
    return {};
  }
};

const writeDrafts = (sessionId, drafts) => {
  if (!sessionId) return;
  try {
    const bounded = Object.fromEntries(Object.entries(drafts)
      .filter(([key, value]) => key && typeof value === "string" && value.length > 0)
      .slice(-MAX_DRAFTS_PER_SESSION)
      .map(([key, value]) => [key, value.slice(0, MAX_DRAFT_LENGTH)]));
    const key = storageKey(sessionId);
    if (Object.keys(bounded).length === 0) sessionStorage.removeItem(key);
    else sessionStorage.setItem(key, JSON.stringify({ expiresAt: Date.now() + DRAFT_TTL_MS, drafts: bounded }));
  } catch { /* Native form submission still works without draft recovery. */ }
};

const saveDraft = (field) => {
  const sessionId = interviewSessionId();
  const drafts = readDrafts(sessionId);
  if (field.value) drafts[field.dataset.draftKey] = field.value;
  else delete drafts[field.dataset.draftKey];
  writeDrafts(sessionId, drafts);
};

const prepareDraftStorage = () => {
  removeLegacyDrafts();
  try {
    Object.keys(sessionStorage).filter((key) => key.startsWith(DRAFT_STORAGE_PREFIX)).forEach((key) => {
      const sessionId = key.slice(DRAFT_STORAGE_PREFIX.length);
      readDrafts(sessionId);
    });
  } catch { /* No-op. */ }
  if (document.querySelector("[data-clear-all-interview-drafts]")) clearAllDrafts();
};

const restoreDrafts = () => {
  prepareDraftStorage();
  const sessionId = interviewSessionId();
  if (!sessionId) return;
  if (document.querySelector("#interview-shell [data-clear-session-drafts]")) {
    try { sessionStorage.removeItem(storageKey(sessionId)); } catch { /* No-op. */ }
    return;
  }
  const drafts = readDrafts(sessionId);
  document.querySelectorAll("#interview-shell [data-clear-draft-key]").forEach((element) => {
    delete drafts[element.dataset.clearDraftKey];
  });
  writeDrafts(sessionId, drafts);
  draftFields().forEach((field) => {
    const saved = drafts[field.dataset.draftKey];
    if (saved !== undefined && field.value === "") field.value = saved;
    field.dataset.draftBound = "true";
  });
};

const setConnectionState = () => {
  const notice = document.querySelector("[data-connection-notice]");
  if (!notice) return;
  notice.hidden = navigator.onLine;
  document.querySelectorAll("#interview-shell form button[type='submit']")
    .forEach((button) => { button.disabled = !navigator.onLine; });
};

const validateComposer = (form) => {
  const textarea = form.querySelector("textarea");
  const option = form.querySelector("input[name='optionId']:checked");
  if (!textarea || option || textarea.value.trim()) {
    textarea?.setCustomValidity("");
    return true;
  }
  textarea.setCustomValidity("Write an answer or choose one of the suggested responses.");
  textarea.reportValidity();
  return false;
};

const bindForms = () => {
  document.querySelectorAll("#interview-shell form[data-draft-form]").forEach((form) => {
    if (form.dataset.enhanced) return;
    form.dataset.enhanced = "true";
    const textarea = form.querySelector("textarea");
    textarea?.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && (event.ctrlKey || event.metaKey)) {
        event.preventDefault();
        form.requestSubmit();
      }
    });
    const button = form.querySelector("button[type='submit']");
    button?.addEventListener("click", (event) => {
      if (form.dataset.submitting === "true") event.preventDefault();
    });
    form.addEventListener("submit", (event) => {
      if (form.matches("[data-answer-form]") && !validateComposer(form)) {
        event.preventDefault();
        return;
      }
      form.dataset.submitting = "true";
      const button = form.querySelector("button[type='submit']");
      const status = form.querySelector(".form-status");
      if (button) {
        button.dataset.originalLabel = button.textContent;
        button.textContent = form.matches("[data-answer-form]") ? "Sending…" : "Saving…";
        // Keep the native submitter enabled: disabling it during submission can
        // cancel navigation. The form-level submitting guard blocks repeats.
        button.setAttribute("aria-disabled", "true");
      }
      if (status) status.textContent = "Waiting for FindWorks to confirm…";
    });
  });
};

let currentFocusKey = null;
const focusCurrentSurface = () => {
  const target = document.querySelector("#interview-shell [data-focus-target]");
  const nextKey = target?.dataset.focusKey ?? null;
  if (!target || !nextKey || nextKey === currentFocusKey) return;
  currentFocusKey = nextKey;
  requestAnimationFrame(() => requestAnimationFrame(() => target.focus({ preventScroll: false })));
};

const bindSurface = () => {
  restoreDrafts();
  bindForms();
  setConnectionState();
  focusCurrentSurface();
};

document.addEventListener("input", (event) => {
  const field = event.target.closest?.("textarea[data-draft-key]");
  if (field) saveDraft(field);
});
window.addEventListener("findworks:auth-ended", clearAllDrafts);
window.addEventListener("online", setConnectionState);
window.addEventListener("offline", setConnectionState);
new MutationObserver(bindSurface).observe(document.documentElement, { childList: true, subtree: true });
bindSurface();
