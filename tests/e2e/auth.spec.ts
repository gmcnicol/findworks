import { expect, test } from '@playwright/test';

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

  await expect(page.getByRole('heading', { name: 'Your Discoveries' })).toBeVisible();
  await expect(page.getByText('Loading your Discoveries…')).toBeVisible();
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
