const draftFields = () => document.querySelectorAll("#interview-shell textarea[data-draft-key]");

const restoreDrafts = () => {
  document.querySelectorAll("#interview-shell [data-clear-draft-key]").forEach((element) => {
    localStorage.removeItem(element.dataset.clearDraftKey);
  });
  draftFields().forEach((field) => {
    const saved = localStorage.getItem(field.dataset.draftKey);
    if (saved !== null && field.value === "") field.value = saved;
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

let currentQuestion = document.querySelector("[data-active-question]")?.dataset.questionId ?? null;
const announceQuestion = () => {
  const active = document.querySelector("[data-active-question]");
  const nextId = active?.dataset.questionId ?? null;
  if (!nextId || nextId === currentQuestion) return;
  currentQuestion = nextId;
  const heading = active.querySelector("h1");
  const announcer = document.querySelector("[data-question-announcer]");
  if (announcer) announcer.textContent = `New question: ${heading?.textContent ?? "Ready"}`;
  heading?.focus({ preventScroll: false });
};

const bindSurface = () => {
  restoreDrafts();
  bindForms();
  setConnectionState();
  announceQuestion();
};

document.addEventListener("input", (event) => {
  const field = event.target.closest?.("textarea[data-draft-key]");
  if (field) localStorage.setItem(field.dataset.draftKey, field.value);
});
window.addEventListener("beforeunload", () => {
  draftFields().forEach((field) => localStorage.setItem(field.dataset.draftKey, field.value));
});
window.addEventListener("online", setConnectionState);
window.addEventListener("offline", setConnectionState);
new MutationObserver(bindSurface).observe(document.documentElement, { childList: true, subtree: true });
bindSurface();
