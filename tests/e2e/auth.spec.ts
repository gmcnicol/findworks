import { expect, test } from '@playwright/test';
import { createHash } from 'node:crypto';

const signIn = async (page, email: string, password = 'findworks') => {
  await page.goto('/signin');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Password').fill(password);
  await page.getByRole('button', { name: 'Continue' }).click();
};

test('verified investigator can sign in, see empty home, and sign out', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByText('Sign in with your verified pilot account')).toBeVisible();

  await signIn(page, 'gareth@example.com');

  await expect(page.getByRole('heading', { name: 'Your work' })).toBeVisible();
  await expect(page.getByRole('navigation', { name: 'Workspace' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'No Discoveries yet' })).toBeVisible();

  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByText('You are signed out.')).toBeVisible();
});

test('inactive user is denied', async ({ page }) => {
  await signIn(page, 'inactive.user@example.com');
  await expect(page.getByRole('heading', { name: 'This account cannot open FindWorks.' })).toBeVisible();
});

test('inactive membership is denied', async ({ page }) => {
  await signIn(page, 'inactive.membership@example.com');
  await expect(page.getByRole('heading', { name: 'This account cannot open FindWorks.' })).toBeVisible();
});

test('another organisation is denied', async ({ page }) => {
  await signIn(page, 'outsider@example.com');
  await expect(page.getByRole('heading', { name: 'This account cannot open FindWorks.' })).toBeVisible();
});

test('investigator authorises and revokes a public harness', async ({ page, request }) => {
  const verifier = 'playwright-verifier-with-at-least-forty-three-characters';
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  const appOrigin = new URL((await request.get('/health')).url()).origin;
  const redirectUri = `${appOrigin}/oauth/callback`;
  const registration = await request.post('/oauth/register', { data: {
    client_name: 'Playwright harness',
    redirect_uris: [redirectUri],
    token_endpoint_auth_method: 'none',
    scope: 'findworks:read',
  }});
  const { client_id } = await registration.json();
  const query = new URLSearchParams({
    response_type: 'code', client_id, redirect_uri: redirectUri,
    state: 'browser-state', code_challenge: challenge, code_challenge_method: 'S256',
    resource: `${appOrigin}/mcp`, scope: 'findworks:read',
  });
  await page.goto(`/oauth/authorize?${query}`);
  await page.getByLabel('Email').fill('gareth@example.com');
  await page.getByLabel('Password').fill('findworks');
  await page.getByRole('button', { name: 'Continue' }).click();
  await expect(page.getByRole('heading', { name: 'Allow this engineer harness?' })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Playwright harness' })).toBeVisible();
  await page.getByRole('button', { name: 'Allow access' }).click();
  const code = new URL(page.url()).searchParams.get('code');
  expect(code).toBeTruthy();
  const token = await request.post('/oauth/token', { form: {
    grant_type: 'authorization_code', code: code!, client_id,
    redirect_uri: redirectUri, code_verifier: verifier,
  }});
  expect(token.ok()).toBeTruthy();

  await page.goto('/oauth/grants');
  await expect(page.getByRole('heading', { name: 'Playwright harness' })).toBeVisible();
  await page.getByRole('button', { name: 'Revoke access' }).click();
  await expect(page.getByText('No active grants.')).toBeVisible();
});
