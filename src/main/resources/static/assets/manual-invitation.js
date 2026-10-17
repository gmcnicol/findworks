const input = document.querySelector('[data-invitation-link]');
const button = document.querySelector('[data-copy-invitation]');
const status = document.querySelector('[data-copy-status]');

const createFeedbackText = (text) => {
  const span = document.createElement('span');
  span.className = 'copy-feedback-text';
  span.textContent = text;
  return span;
};

if (input && button && status) {
  button.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(input.value);
      button.replaceChildren(createFeedbackText('Copied'));
      status.replaceChildren(createFeedbackText('Copied. You can now paste the link into your message.'));
    } catch {
      input.focus();
      input.select();
      status.replaceChildren(createFeedbackText('Copy was blocked. The link is selected; copy it manually.'));
    }
  });
}
