const timeout = async (promise, milliseconds = 15_000) => {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('Sign-in timed out')), milliseconds); }),
    ]);
  } finally { clearTimeout(timer); }
};

export async function verifySession(clerk, request = fetch) {
  const token = await clerk.session.getToken({ skipCache: true });
  if (!token) return 'expired';
  const response = await request('/auth/check', {
    headers: { Authorization: `Bearer ${token}` }, credentials: 'same-origin', cache: 'no-store',
    signal: AbortSignal.timeout(10_000),
  });
  if (response.status === 204) return 'allowed';
  if (response.status === 403) return 'denied';
  if (response.status === 401) return 'expired';
  return 'unavailable';
}

export async function initialiseClerk(window, document) {
  const container = document.querySelector('[data-clerk-sign-in]');
  const status = document.querySelector('[data-clerk-auth-status]');
  const error = document.querySelector('[data-clerk-auth-error]');
  const switchAccount = document.querySelector('[data-clerk-switch-account]');
  const showError = (message) => {
    if (status) status.hidden = true;
    if (error) {
      error.hidden = false;
      error.querySelector('[data-clerk-auth-error-message]').textContent = message;
    }
  };
  document.querySelector('[data-clerk-auth-retry]')?.addEventListener('click', () => window.location.reload());
  const signOut = async (button, destination) => {
    button.disabled = true;
    try {
      if (!window.Clerk?.loaded) throw new Error('Sign-in has not loaded');
      await timeout(window.Clerk.signOut());
      window.dispatchEvent(new Event('findworks:auth-ended'));
      window.location.assign(destination);
    } catch {
      button.disabled = false;
      const signOutError = document.querySelector('[data-clerk-sign-out-error]');
      if (signOutError) signOutError.hidden = false;
      showError('Sign-out failed. Check your connection and try again.');
    }
  };
  document.querySelectorAll('[data-clerk-sign-out]').forEach((button) => {
    button.addEventListener('click', () => void signOut(button, '/?signed_out=1'));
  });
  switchAccount?.addEventListener('click', () => void signOut(switchAccount, switchAccount.dataset.clerkSwitchDestination || '/signin'));

  try {
    if (!window.Clerk) throw new Error('Secure sign-in did not load');
    const ui = window.__internal_ClerkUICtor;
    await timeout(window.Clerk.load(ui ? { ui: { ClerkUI: ui } } : {}));
    if (!container) return;
    const destination = document.querySelector('[data-sign-in-destination]').dataset.signInDestination;
    if (window.Clerk.isSignedIn && window.Clerk.session) {
      if (status) status.textContent = 'Restoring your saved work…';
      if (switchAccount) switchAccount.hidden = false;
      try {
        const result = await verifySession(window.Clerk);
        if (result === 'allowed') window.location.replace(destination);
        else if (result === 'denied') window.location.replace('/denied');
        else throw new Error(result === 'expired'
          ? 'Your session could not be restored. Try again or use a different account.'
          : 'FindWorks could not verify your access. Your saved work is unchanged. Try again shortly.');
      } catch (failure) { showError(failure.message); }
      return;
    }
    if (!ui) throw new Error('Clerk login screens did not load');
    // Always return through fresh server verification, not straight to protected work.
    // Clerk owns its forms, email-code flow and invitation-ticket acceptance.
    const signInUrl = `/signin?returnTo=${encodeURIComponent(destination)}`;
    const signUpUrl = `/signup?returnTo=${encodeURIComponent(destination)}`;
    const props = { routing: 'hash', forceRedirectUrl: signInUrl };
    if (container.dataset.clerkComponent === 'sign-up') {
      window.Clerk.mountSignUp(container, { ...props, signInUrl, signInForceRedirectUrl: signInUrl });
    } else {
      window.Clerk.mountSignIn(container, { ...props, signUpUrl, signUpForceRedirectUrl: signInUrl });
    }
    if (status) status.hidden = true;
  } catch { showError('Secure sign-in could not load. Check your connection and try again.'); }
}

if (typeof window !== 'undefined' && typeof document !== 'undefined') {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => void initialiseClerk(window, document), { once: true });
  } else { void initialiseClerk(window, document); }
}
