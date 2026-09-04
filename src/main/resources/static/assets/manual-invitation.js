const input = document.querySelector('[data-invitation-link]');
const button = document.querySelector('[data-copy-invitation]');
const status = document.querySelector('[data-copy-status]');

button?.addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(input.value);
    button.textContent = 'Copied';
    status.textContent = 'Copied. You can now paste the link into your message.';
  } catch {
    input.focus();
    input.select();
    status.textContent = 'Copy was blocked. The link is selected; copy it manually.';
  }
});
