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

  await expect(page.locator('#active-question')).toBeFocused();
  await expect(page.locator('[data-question-announcer]')).toHaveCount(0);
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

test('only one composer is visible while moving between answer and revision modes', async ({ page, request }) => {
  const data = await fixture(request, 'single-composer@example.com');
  await begin(page, data);
  const seeded = await request.post(`/test/interviews/${data.session_id}/long-conversation`);
  expect(seeded.ok()).toBeTruthy();
  await page.reload();

  await expect(page.locator('textarea:visible')).toHaveCount(1);
  await page.getByRole('link', { name: 'Correct this response' }).first().click();
  await expect(page.getByRole('heading', { name: 'Correct your saved response' })).toBeFocused();
  await expect(page.locator('form[data-revision-form]')).toHaveCount(1);
  await expect(page.locator('form[data-answer-form]')).toHaveCount(0);
  await expect(page.locator('textarea:visible')).toHaveCount(1);

  const firstRevisionUrl = page.url();
  await page.getByRole('link', { name: 'Correct this response' }).nth(1).click();
  await expect(page).not.toHaveURL(firstRevisionUrl);
  await expect(page.locator('form[data-revision-form]')).toHaveCount(1);
  await expect(page.locator('form[data-answer-form]')).toHaveCount(0);
  await expect(page.locator('textarea:visible')).toHaveCount(1);

  await page.getByRole('link', { name: 'Cancel correction' }).click();
  await expect(page.locator('form[data-revision-form]')).toHaveCount(0);
  await expect(page.locator('form[data-answer-form]')).toHaveCount(1);
  await expect(page.locator('textarea:visible')).toHaveCount(1);
});

test('long-session lifecycle actions load before the transcript, receive focus, and stay protected offline', async ({ context, page, request }) => {
  const states = [
    { state: 'PAUSED', heading: 'Your place is saved', action: 'Resume interview' },
    { state: 'RUNTIME_FAILED', heading: 'We couldn’t prepare the next question', action: 'Try again' },
    { state: 'AWAITING_CONFIRMATION', heading: 'Ready to finish?', action: 'Confirm and finish' },
    { state: 'COMPLETED', heading: 'Thank you', action: null },
  ] as const;

  await page.setViewportSize({ width: 390, height: 844 });
  for (const lifecycle of states) {
    const data = await fixture(request, `long-${lifecycle.state.toLowerCase()}@example.com`);
    await begin(page, data);
    expect((await request.post(`/test/interviews/${data.session_id}/long-conversation`)).ok()).toBeTruthy();
    expect((await request.post(`/test/interviews/${data.session_id}/state`, { form: { state: lifecycle.state } })).ok()).toBeTruthy();
    await page.reload();

    const heading = page.getByRole('heading', { name: lifecycle.heading });
    await expect(heading).toBeFocused();
    await expect(heading).toBeInViewport();
    const order = await page.locator('.conversation-state, .conversation-transcript').evaluateAll((elements) =>
      elements.map((element) => element.className),
    );
    expect(order[0]).toContain('conversation-state');
    expect(order[1]).toContain('conversation-transcript');

    if (lifecycle.action) {
      await context.setOffline(true);
      await expect(page.getByText('You’re offline.')).toBeVisible();
      await expect(page.getByRole('button', { name: lifecycle.action })).toBeDisabled();
      await context.setOffline(false);
    }
  }
});

test('a resumed 20+ turn interview stays readable and exposes one current question', async ({ page, request }) => {
  const data = await fixture(request, 'long-transcript@example.com');
  await begin(page, data);
  const seeded = await request.post(`/test/interviews/${data.session_id}/long-conversation`);
  expect(seeded.ok()).toBeTruthy();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();

  await expect(page.getByText('Exploring 1 key area', { exact: true })).toBeVisible();
  await expect(page.locator('.transcript-exchange')).toHaveCount(21);
  await expect(page.locator('[data-active-question]')).toHaveCount(1);
  await expect(page.locator('form[data-answer-form]')).toHaveCount(1);
  await expect(page.locator('textarea:visible')).toHaveCount(1);
  await expect(page.getByText('I need to correct that: Gareth does not approve every mismatch.', { exact: false })).toBeVisible();
  await expect(page.getByText('Correction · saved', { exact: true })).toHaveCount(1);
  await expect(page.getByText('This correction was appended. The earlier response remains in the transcript.', { exact: true })).toBeVisible();
  await expect(page.getByText('No. My first wording was too broad;', { exact: false })).toBeVisible();
  const activeQuestion = page.getByRole('heading', { name: 'Is there anything important about mismatch decisions that we have missed?' });
  await expect(activeQuestion).toBeFocused();
  await expect(activeQuestion).toBeInViewport();
  await expect(page.locator('.transcript-list')).toHaveAttribute('role', 'log');
  await expect(page.getByRole('complementary')).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
});
