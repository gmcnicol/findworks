import { expect, test, type Page } from '@playwright/test';

const fixture = async (request, email: string) => {
  const response = await request.post('/test/fixtures/approved-mission', { form: { email } });
  expect(response.ok()).toBeTruthy();
  return response.json();
};

const begin = async (page: Page, data) => {
  await page.goto(data.invitation_url);
  await page.getByRole('button', { name: 'Continue to invitation' }).click();
  await page.getByRole('button', { name: 'Begin interview' }).click();
  await expect(page.locator('[data-active-question]')).toHaveCount(1);
};

test('drafts survive reload and disconnection while one composer remains actionable', async ({ context, page, request }) => {
  const data = await fixture(request, 'draft-safety@example.com');
  await begin(page, data);

  const composer = page.getByLabel('Your answer in your own words');
  const draft = 'Priya checks the cashbook first, then compares the source ledger when the totals differ.';
  await composer.fill(draft);
  await page.reload();
  await expect(page.getByLabel('Your answer in your own words')).toHaveValue(draft);
  await expect(page.locator('textarea:visible')).toHaveCount(1);

  await context.setOffline(true);
  await expect(page.getByText('You’re offline.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Submit answer' })).toBeDisabled();
  await context.setOffline(false);
  await expect(page.getByText('You’re offline.')).toBeHidden();
  await expect(page.getByRole('button', { name: 'Submit answer' })).toBeEnabled();

  await composer.press(process.platform === 'darwin' ? 'Meta+Enter' : 'Control+Enter');
  await expect(page.getByRole('heading', { name: 'Preparing your next question' })).toBeVisible();
  await expect(page.locator('.transcript-response').getByText(draft, { exact: true })).toHaveCount(1);
  await request.post(`/test/runtime/run-once?sessionId=${data.session_id}`);
  await expect(page.locator('[data-active-question]')).toHaveCount(1);
  await expect(page.locator('#active-question')).toBeFocused();
});

test('a resumed 20+ turn interview stays readable and exposes one current question', async ({ page, request }) => {
  const data = await fixture(request, 'long-transcript@example.com');
  await begin(page, data);
  const seeded = await request.post(`/test/interviews/${data.session_id}/long-conversation`);
  expect(seeded.ok()).toBeTruthy();
  await page.reload();

  await expect(page.locator('.transcript-exchange')).toHaveCount(21);
  await expect(page.locator('[data-active-question]')).toHaveCount(1);
  await expect(page.locator('form[data-answer-form]')).toHaveCount(1);
  await expect(page.locator('textarea:visible')).toHaveCount(1);
  await expect(page.getByText('I need to correct that: Gareth does not approve every mismatch.', { exact: false })).toBeVisible();
  await expect(page.getByText('No. My first wording was too broad;', { exact: false })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Is there anything important about mismatch decisions that we have missed?' })).toBeVisible();
  await expect(page.locator('.transcript-list')).toHaveAttribute('role', 'log');
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();

  await page.setViewportSize({ width: 390, height: 844 });
  await page.locator('[data-active-question]').scrollIntoViewIfNeeded();
  await expect(page.getByRole('button', { name: 'Submit answer' })).toBeInViewport();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
});
