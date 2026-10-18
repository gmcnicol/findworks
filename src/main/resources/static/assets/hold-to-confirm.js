const HOLD_DURATION_MS = 2000;

for (const button of document.querySelectorAll('[data-hold-confirm]')) {
  if (button.dataset.holdReady === 'true') continue;

  const form = button.form;
  const label = button.querySelector('[data-hold-label-text]');
  const status = form?.querySelector('[data-hold-status]');
  const originalLabel = button.dataset.holdLabel;

  if (!form || !label || !status || !originalLabel) continue;

  let timer = null;
  let activeInput = null;
  let submitting = false;

  const cancelHold = () => {
    if (timer === null || submitting) return;

    window.clearTimeout(timer);
    timer = null;
    activeInput = null;
    button.classList.remove('is-holding');
    label.textContent = originalLabel;
    status.textContent = 'Action cancelled.';
  };

  const completeHold = () => {
    if (timer === null || submitting) return;

    window.clearTimeout(timer);
    timer = null;
    activeInput = null;
    submitting = true;
    button.classList.remove('is-holding');
    label.textContent = 'Completing…';
    status.textContent = 'Completing…';
    button.disabled = true;
    form.requestSubmit(button);
  };

  const startHold = (input) => {
    if (timer !== null || submitting) return;

    activeInput = input;
    button.classList.add('is-holding');
    label.textContent = 'Keep holding…';
    status.textContent = 'Keep holding to confirm.';
    timer = window.setTimeout(completeHold, HOLD_DURATION_MS);
  };

  button.addEventListener('pointerdown', (event) => {
    if (event.button !== 0) return;
    startHold('pointer');
  });
  button.addEventListener('pointerup', () => {
    if (activeInput === 'pointer') cancelHold();
  });
  button.addEventListener('pointercancel', () => {
    if (activeInput === 'pointer') cancelHold();
  });
  button.addEventListener('pointerleave', () => {
    if (activeInput === 'pointer') cancelHold();
  });
  button.addEventListener('keydown', (event) => {
    if (event.key !== ' ' && event.key !== 'Enter') return;
    event.preventDefault();
    if (!event.repeat) startHold(event.key);
  });
  button.addEventListener('keyup', (event) => {
    if (event.key !== ' ' && event.key !== 'Enter') return;
    event.preventDefault();
    if (activeInput === event.key) cancelHold();
  });
  button.addEventListener('blur', cancelHold);
  button.addEventListener('click', (event) => {
    if (button.dataset.holdReady === 'true') event.preventDefault();
  });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) cancelHold();
  });

  button.dataset.holdReady = 'true';
}
