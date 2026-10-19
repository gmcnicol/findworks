import { expect, test, type Page } from '@playwright/test';

// Application/SDK contracts only. These stubs do not claim live Clerk acceptance.
const panel = (destination: string, component = 'sign-in') => `
  <section data-sign-in-destination="${destination}">
    <h1>FindWorks</h1><p role="status" data-clerk-auth-status>Loading secure sign-in…</p>
    <div data-clerk-sign-in data-clerk-component="${component}"></div>
    <div hidden role="alert" data-clerk-auth-error><p data-clerk-auth-error-message></p><button data-clerk-auth-retry>Try again</button></div>
    <button hidden data-clerk-switch-account data-clerk-switch-destination="/signin?returnTo=${encodeURIComponent(destination)}">Use a different account</button>
  </section><script type="module" src="/assets/clerk-auth.js"></script>`;

const mountSignIn = async (page: Page, result: number, destination = '/app/missions/123/review') => {
  let checks = 0;
  await page.addInitScript(() => {
    (window as any).__internal_ClerkUICtor = function () {};
    (window as any).Clerk = {
      loaded: true, isSignedIn: true, load: async () => {},
      session: { getToken: async (options) => { (window as any).__refreshOptions = options; return 'fresh-session-token'; } },
      signOut: async () => { (window as any).__signedOut = true; },
    };
  });
  await page.route('**/auth/check', async (route) => {
    checks++;
    expect(route.request().headers().authorization).toBe('Bearer fresh-session-token');
    await route.fulfill({ status: result });
  });
  await page.route(`**${destination}`, (route) => route.fulfill({ body: '<h1>Returned to the requested work</h1>', contentType: 'text/html' }));
  await page.route('**/denied', (route) => route.fulfill({ body: '<h1>Membership required</h1>', contentType: 'text/html' }));
  await page.route('**/signin', (route) => route.fulfill({ contentType: 'text/html', body: panel(destination) }));
  await page.goto('/signin');
  return () => checks;
};

test('refreshes a session token and restores the requested destination rather than the dashboard', async ({ page }) => {
  const checks = await mountSignIn(page, 204);
  await expect(page.getByRole('heading', { name: 'Returned to the requested work' })).toBeVisible();
  await expect(page).toHaveURL(/\/app\/missions\/123\/review$/);
  expect(checks()).toBe(1);
});

test('identity verification rejection goes to access denied, not a sign-in loop', async ({ page }) => {
  const checks = await mountSignIn(page, 403);
  await expect(page.getByRole('heading', { name: 'Membership required' })).toBeVisible();
  expect(checks()).toBe(1);
});

for (const result of [401, 503]) {
  test(`failed restoration (${result}) stays on sign-in with explicit recovery`, async ({ page }) => {
    const checks = await mountSignIn(page, result);
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Use a different account' })).toBeVisible();
    await expect(page).toHaveURL(/\/signin$/);
    expect(await page.evaluate(() => (window as any).__refreshOptions)).toEqual({ skipCache: true });
    expect(checks()).toBe(1);
  });
}

const nativeClient = async (page: Page, start = '/signin?returnTo=%2Finterviews', uiAvailable = true) => {
  await page.addInitScript(({ uiAvailable }) => {
    if (uiAvailable) (window as any).__internal_ClerkUICtor = function () {};
    const mount = (name, element, props) => {
      (window as any).__mountedComponent = name;
      (window as any).__mountedProps = props;
      const button = document.createElement('button');
      button.textContent = 'Complete Clerk authentication';
      button.onclick = () => {
        sessionStorage.setItem('test-clerk-session', 'active');
        sessionStorage.setItem('test-clerk-redirect', props.forceRedirectUrl);
        window.location.assign(props.forceRedirectUrl);
      };
      element.append(button);
    };
    (window as any).Clerk = {
      loaded: true, isSignedIn: sessionStorage.getItem('test-clerk-session') === 'active',
      load: async (options) => { (window as any).__uiConfigured = !!options.ui?.ClerkUI; },
      session: { getToken: async (options) => { sessionStorage.setItem('test-refresh-options', JSON.stringify(options)); return 'native-session-token'; } },
      mountSignIn: (element, props) => mount('SignIn', element, props),
      mountSignUp: (element, props) => mount('SignUp', element, props),
      signOut: async () => { sessionStorage.removeItem('test-clerk-session'); sessionStorage.setItem('test-signed-out', 'true'); },
    };
  }, { uiAvailable });
  for (const route of ['**/signin**', '**/signup**']) {
    await page.route(route, (request) => {
      const url = new URL(request.request().url());
      const component = url.pathname === '/signup' || url.searchParams.get('__clerk_status') === 'sign_up' ? 'sign-up' : 'sign-in';
      return request.fulfill({ contentType: 'text/html', body: panel(url.searchParams.get('returnTo') || '/work', component) });
    });
  }
  await page.route('**/auth/check', (route) => {
    expect(route.request().headers().authorization).toBe('Bearer native-session-token');
    return route.fulfill({ status: 204 });
  });
  await page.route('**/interviews', (route) => route.fulfill({ contentType: 'text/html', body: '<h1>Your saved interviews</h1>' }));
  await page.route('**/invite/test-invitation', (route) => route.fulfill({ contentType: 'text/html', body: '<h1>Accept this interview</h1>' }));
  await page.goto(start);
};

for (const component of ['SignIn', 'SignUp']) {
  test(`mounts Clerk's standard ${component} UI and verifies a fresh token before returning to saved work`, async ({ page }) => {
    await nativeClient(page, `${component === 'SignUp' ? '/signup' : '/signin'}?returnTo=%2Finterviews`);
    await expect(page.getByRole('button', { name: 'Complete Clerk authentication' })).toBeVisible();
    expect(await page.evaluate(() => (window as any).__mountedComponent)).toBe(component);
    expect(await page.evaluate(() => (window as any).__uiConfigured)).toBe(true);
    const props = await page.evaluate(() => (window as any).__mountedProps);
    expect(props.routing).toBe('hash');
    expect(props.forceRedirectUrl).toBe('/signin?returnTo=%2Finterviews');
    expect(props[component === 'SignIn' ? 'signUpUrl' : 'signInUrl']).toBe(`${component === 'SignIn' ? '/signup' : '/signin'}?returnTo=%2Finterviews`);
    await page.getByRole('button', { name: 'Complete Clerk authentication' }).click();
    await expect(page.getByRole('heading', { name: 'Your saved interviews' })).toBeVisible();
    expect(await page.evaluate(() => JSON.parse(sessionStorage.getItem('test-refresh-options')!))).toEqual({ skipCache: true });
  });
}

for (const newAccount of [true, false]) {
  test(`${newAccount ? 'new' : 'returning'} recipient uses the native Clerk invitation screen and returns to the exact interview`, async ({ page }) => {
    await nativeClient(page, `/signin?__clerk_ticket=test-ticket&__clerk_status=${newAccount ? 'sign_up' : 'sign_in'}&returnTo=%2Finvite%2Ftest-invitation`);
    await expect(page.getByRole('button', { name: 'Complete Clerk authentication' })).toBeVisible();
    expect(await page.evaluate(() => (window as any).__mountedComponent)).toBe(newAccount ? 'SignUp' : 'SignIn');
    await page.getByRole('button', { name: 'Complete Clerk authentication' }).click();
    await expect(page.getByRole('heading', { name: 'Accept this interview' })).toBeVisible();
    expect(await page.evaluate(() => sessionStorage.getItem('test-clerk-redirect'))).toBe('/signin?returnTo=%2Finvite%2Ftest-invitation');
  });
}

test('account switching clears interview drafts and preserves the requested interview destination', async ({ page }) => {
  await nativeClient(page);
  await page.evaluate(() => {
    sessionStorage.setItem('findworks:interview-drafts:v1:session-68', '{"drafts":{"question":"private draft"}}');
    document.querySelector<HTMLButtonElement>('[data-clerk-switch-account]')!.hidden = false;
  });
  await page.getByRole('button', { name: 'Use a different account' }).click();
  await expect.poll(() => page.evaluate(() => sessionStorage.getItem('test-signed-out'))).toBe('true');
  expect(await page.evaluate(() => sessionStorage.getItem('findworks:interview-drafts:v1:session-68'))).toBeNull();
  await expect(page).toHaveURL(/\/signin\?returnTo=%2Finterviews$/);
});

test('unavailable Clerk UI bundle shows recovery rather than an empty login screen', async ({ page }) => {
  await nativeClient(page, '/signin?returnTo=%2Finterviews', false);
  await expect(page.getByRole('alert')).toContainText('Check your connection');
  await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
});

test('unavailable SDK shows a recoverable error rather than a blank sign-in panel', async ({ page }) => {
  await page.route('**/signin', (route) => route.fulfill({ contentType: 'text/html', body: panel('/work') }));
  await page.goto('/signin');
  await expect(page.getByRole('alert')).toContainText('Check your connection');
  await expect(page.getByRole('button', { name: 'Try again' })).toBeVisible();
});
