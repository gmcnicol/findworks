import { expect, test, type Page } from '@playwright/test';

const signIn = async (page: Page) => {
  await page.goto('/signin');
  await page.getByLabel('Email').fill('gareth@example.com');
  await page.getByLabel('Password').fill('findworks');
  await page.getByRole('button', { name: 'Continue' }).click();
};

test('returning Investigator finds the first unfinished checkpoint and then approval', async ({ browser, page, request }) => {
  const data = await (await request.post('/test/fixtures/draft-mission', { form: { email: 'return-review@example.com' } })).json();
  await signIn(page);
  await page.goto(data.review_url);
  await page.getByRole('button', { name: 'Mark reviewed · Purpose', exact: true }).click();
  await page.getByRole('button', { name: 'Mark reviewed · Investigation Items', exact: true }).click();
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await page.close();

  const returning = await browser.newPage();
  await signIn(returning);
  await returning.goto(`/app/discoveries/${data.discovery_id}`);
  const reviewPath = new URL(data.review_url).pathname;
  const continueReview = returning.locator(`a[href="${reviewPath}#interviewee-heading"]`);
  await expect(continueReview).toContainText('Continue Mission review');
  await continueReview.click();
  await expect(returning).toHaveURL(/#interviewee-heading$/);
  await expect(returning.getByLabel('Review progress')).toContainText('2 of 5');
  for (const name of ['Intended interviewee', 'Shared context and boundaries', 'Completion and data use']) {
    await returning.getByRole('button', { name: `Mark reviewed · ${name}`, exact: true }).click();
  }
  await returning.goto(`/app/discoveries/${data.discovery_id}`);
  await expect(returning.locator(`a[href="${reviewPath}#approval-decision"]`)).toContainText('Approve Mission');
  await returning.close();
});

test('Interviewee returns on a saved browser grant without repeating an accepted answer', async ({ browser, request }, testInfo) => {
  const data = await (await request.post('/test/fixtures/approved-mission', { form: { email: 'return-interview@example.com' } })).json();
  const original = await browser.newContext();
  const page = await original.newPage();
  await page.goto(data.invitation_url);
  await page.getByRole('button', { name: 'Continue to invitation' }).click();
  await page.getByRole('button', { name: 'Begin interview' }).click();
  const answer = 'The finance analyst checks the imported amount against the source ledger.';
  await page.getByLabel('Your answer in your own words').fill(answer);
  await page.getByRole('button', { name: 'Submit answer' }).click();
  await expect(page.getByRole('heading', { name: 'Preparing your next question' })).toBeVisible();
  await request.post(`/test/runtime/run-once?sessionId=${data.session_id}`);
  await expect(page.getByRole('heading', { name: 'What does the finance analyst check in the source ledger?' })).toBeVisible();
  await page.getByRole('button', { name: 'Pause and leave' }).click();
  await expect(page.getByRole('heading', { name: 'Your place is saved' })).toBeVisible();
  const savedBrowser = await original.storageState();
  await original.close();

  const resumed = await browser.newContext({ storageState: savedBrowser });
  const returning = await resumed.newPage();
  await returning.goto('/interview');
  await expect(returning.getByRole('heading', { name: 'Your place is saved' })).toBeVisible();
  await returning.getByRole('button', { name: 'Resume interview' }).click();
  await expect(returning.getByRole('heading', { name: 'What does the finance analyst check in the source ledger?' })).toBeVisible();
  await expect(returning.getByRole('heading', { name: 'Conversation transcript' })).toBeAttached();
  await expect(returning.locator('.transcript-exchange')).toHaveCount(1);
  await expect(returning.locator('.transcript-response').getByText(answer, { exact: true })).toHaveCount(1);

  const investigator = await browser.newPage();
  await signIn(investigator);
  await investigator.goto(`/app/interviews/${data.session_id}`);
  await expect(investigator.getByRole('heading', { name: 'Interview in progress', exact: true })).toBeVisible();
  await expect(investigator.getByText('Accepted answers')).toBeVisible();
  await expect(investigator.getByText(answer)).toHaveCount(0);
  await investigator.screenshot({ path: testInfo.outputPath('interview-status-desktop.png'), fullPage: true });
  await investigator.setViewportSize({ width: 390, height: 844 });
  await investigator.screenshot({ path: testInfo.outputPath('interview-status-mobile.png'), fullPage: true });
  expect(await investigator.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  await investigator.close();
  await resumed.close();
});
