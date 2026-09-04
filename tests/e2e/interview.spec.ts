import { expect, test } from '@playwright/test';

const fixture = async (request, email: string) => {
  const response = await request.post('/test/fixtures/approved-mission', { form: { email } });
  expect(response.ok()).toBeTruthy();
  return response.json();
};

const begin = async (page, data) => {
  await page.goto(data.invitation_url);
  await page.getByRole('button', { name: 'Continue to invitation' }).click();
  await expect(page.getByRole('heading', { name: 'Before you begin' })).toBeVisible();
  await expect(page.getByText('20 minutes')).toBeVisible();
  await expect(page.getByText('90 days after findings review')).toBeVisible();
  await page.getByRole('button', { name: 'Begin interview' }).click();
  await expect(page.getByRole('heading', { name: 'Walk me through the last mismatch you resolved.' })).toBeVisible();
};

const signIn = async (page) => {
  await page.goto('/signin');
  await page.getByLabel('Email').fill('gareth@example.com');
  await page.getByLabel('Password').fill('findworks');
  await page.getByRole('button', { name: 'Continue' }).click();
};

test('investigator reviews, renames, approves, and supersedes an exact Mission version', async ({ browser, page, request }) => {
  const response = await request.post('/test/fixtures/draft-mission', { form: { email: 'mission-review@example.com' } });
  const data = await response.json();
  await signIn(page);
  await page.goto(data.review_url);
  await expect(page.getByText('Draft · Version 1')).toBeVisible();
  await expect(page.getByLabel('Review progress').getByText('0 of 5 checkpoints reviewed')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Approve this version' })).toBeDisabled();

  await page.getByText('Edit this Mission').click();
  await page.getByLabel('Administrative label').fill('Monthly reconciliation decisions');
  await page.getByRole('button', { name: 'Rename Mission' }).click();
  await expect(page.getByRole('heading', { name: 'Monthly reconciliation decisions' })).toBeVisible();
  for (let checkpoint = 0; checkpoint < 5; checkpoint++) {
    await page.locator('button:not([disabled])').filter({ hasText: 'Mark reviewed' }).first().click();
  }
  await expect(page.getByLabel('Review progress').getByText('5 of 5 checkpoints reviewed')).toBeVisible();
  await page.getByRole('button', { name: 'Approve this version' }).click();
  await expect(page.getByText('Approved · Version 1')).toBeVisible();
  await expect(page.getByText('An invitation link is active.')).toBeVisible();

  const unusedAccess = await browser.newPage();
  await unusedAccess.goto(data.invitation_url);
  await unusedAccess.getByRole('button', { name: 'Continue to invitation' }).click();
  await expect(unusedAccess.getByRole('heading', { name: 'Before you begin' })).toBeVisible();

  await page.getByText('Edit this Mission').click();
  const objectiveForm = page.getByRole('button', { name: 'Save Objective' }).locator('..');
  await objectiveForm.getByRole('textbox').fill('Understand exception handling in monthly reconciliation');
  await page.getByRole('button', { name: 'Save Objective' }).click();
  await expect(page.getByText('Draft · Version 2')).toBeVisible();
  await expect(page.getByText('Approval superseded.')).toBeVisible();
  expect((await unusedAccess.reload())?.status()).toBe(403);
  await unusedAccess.close();

  await request.post('/test/fixtures/non-owner');
  const nonOwner = await browser.newPage();
  await nonOwner.goto('/signin');
  await nonOwner.getByLabel('Email').fill('reviewer@example.com');
  await nonOwner.getByLabel('Password').fill('findworks');
  await nonOwner.getByRole('button', { name: 'Continue' }).click();
  const denied = await nonOwner.goto(data.review_url);
  expect(denied?.status()).toBe(404);
  await nonOwner.close();
});

test('investigator generates and copies a manual invitation link', async ({ browser, page, request }) => {
  const data = await fixture(request, 'manual-link@example.com');
  await signIn(page);
  await page.goto(data.review_url);

  const invitationSection = page.getByRole('heading', { name: 'Invite the interviewee' }).locator('..');
  await invitationSection.getByLabel('Name').fill('Clare');
  await invitationSection.getByLabel('Email').fill('clare@example.com');
  await invitationSection.getByRole('button', { name: 'Generate link' }).click();

  await expect(page.getByRole('heading', { name: 'Copy invitation link' })).toBeVisible();
  const link = await page.getByRole('textbox', { name: 'Invitation link' }).inputValue();
  expect(new URL(link).origin).toBe(new URL(page.url()).origin);
  expect(new URL(link).pathname).toMatch(/^\/invite\//);
  await page.context().grantPermissions(['clipboard-read', 'clipboard-write']);
  await page.getByRole('button', { name: 'Copy link' }).click();
  await expect(page.getByRole('status')).toContainText('Copied');
  expect(await page.evaluate(() => navigator.clipboard.readText())).toBe(link);

  const preview = await browser.newPage();
  await preview.goto(link);
  await expect(preview.getByRole('button', { name: 'Continue to invitation' })).toBeVisible();
  await preview.close();

  const interviewee = await browser.newPage();
  await interviewee.goto(link);
  await interviewee.getByRole('button', { name: 'Continue to invitation' }).click();
  await expect(interviewee.getByRole('heading', { name: 'Before you begin' })).toBeVisible();
  await interviewee.close();
});

test('answer, adaptive structured follow-up, completion, and findings review', async ({ page, request }) => {
  const data = await fixture(request, 'happy-path@example.com');
  await begin(page, data);
  await page.getByLabel('Your answer in your own words').fill('The finance analyst checks the imported amount against the source ledger.');
  await page.getByRole('button', { name: 'Submit answer' }).click();
  await expect(page.getByRole('heading', { name: 'Preparing your next question' })).toBeVisible();
  await expect(page.getByRole('complementary').getByText('The finance analyst checks the imported amount')).toBeVisible();
  await request.post(`/test/runtime/run-once?sessionId=${data.session_id}`);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'What does the finance analyst check in the source ledger?' })).toBeVisible();
  await page.getByLabel('Partly').check();
  await page.getByLabel('Anything to add?').fill('Only unmatched entries need approval.');
  await page.getByRole('button', { name: 'Submit response' }).click();
  await request.post(`/test/runtime/run-once?sessionId=${data.session_id}`);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'Ready to finish?' })).toBeVisible();
  await page.getByRole('button', { name: 'Confirm and finish' }).click();
  await expect(page.getByRole('heading', { name: 'Thank you' })).toBeVisible();

  await signIn(page);
  await page.goto(`/app/findings/${data.session_id}`);
  await expect(page.getByRole('heading', { name: 'Review findings' })).toBeVisible();
  await expect(page.getByText('Extraction pending')).toBeVisible();
  await request.post('/test/extraction/run-once');
  await page.reload();
  await page.getByText('Open source context').click();
  await expect(page.getByRole('group').getByText('Only unmatched entries need approval.')).toBeVisible();
  await page.getByRole('button', { name: 'Reject', exact: true }).click();
  await expect(page.getByText('REJECTED', { exact: true })).toBeVisible();
  await page.getByLabel('Corrected claim').fill('Only unmatched entries require Gareth approval.');
  await page.getByRole('button', { name: 'Correct' }).click();
  await expect(page.getByText('ACCEPTED', { exact: true })).toBeVisible();
  await page.getByLabel('Acceptance notes (optional)').fill('Ready for implementation.');
  await page.getByRole('button', { name: 'Accept exact package' }).click();
  await expect(page.getByText('Decision · ACCEPTED')).toBeVisible();
  await expect(page.getByText('Ready for implementation.')).toBeVisible();
  await expect(page.getByText('Package Version 1')).toBeVisible();
});

test('uncertainty, ownership, clarification, pause, and early ending stay honest', async ({ browser, request }) => {
  const unknownData = await fixture(request, 'unknown@example.com');
  const unknown = await browser.newPage();
  await begin(unknown, unknownData);
  await unknown.getByRole('button', { name: 'I don’t know' }).click();
  await expect(unknown.getByText('Interviewee did not know')).toBeVisible();
  await unknown.getByRole('button', { name: 'Confirm and finish' }).click();
  await request.post('/test/extraction/run-once');
  await unknown.close();

  const ownerData = await fixture(request, 'owner@example.com');
  const owner = await browser.newPage();
  await begin(owner, ownerData);
  await owner.getByLabel('Owner').fill('Treasury lead');
  await owner.getByLabel('What they own').fill('They decide which mismatch is acceptable.');
  await owner.getByRole('button', { name: 'Someone else owns this' }).click();
  await expect(owner.getByText('Treasury lead')).toBeVisible();
  await owner.close();

  const clarifyData = await fixture(request, 'clarify@example.com');
  const clarify = await browser.newPage();
  await begin(clarify, clarifyData);
  await clarify.getByRole('button', { name: 'Please clarify' }).click();
  await expect(clarify.getByRole('heading', { name: /Let me put that another way/ })).toBeVisible();
  await clarify.getByRole('button', { name: 'Please clarify' }).click();
  await expect(clarify.getByText('Question remained unclear')).toBeVisible();
  await clarify.close();

  const discomfortData = await fixture(request, 'discomfort@example.com');
  const discomfort = await browser.newPage();
  await begin(discomfort, discomfortData);
  await discomfort.getByRole('button', { name: 'This feels uncomfortable' }).click();
  await expect(discomfort.getByText('Interviewee chose not to continue this line')).toBeVisible();
  await discomfort.close();

  const revisionData = await fixture(request, 'revision@example.com');
  const revision = await browser.newPage();
  await begin(revision, revisionData);
  await revision.getByLabel('Your answer in your own words').fill('All imported amounts need approval.');
  await revision.getByRole('button', { name: 'Submit answer' }).click();
  await request.post(`/test/runtime/run-once?sessionId=${revisionData.session_id}`);
  await revision.reload();
  await revision.getByText('Review or revise accepted answers').click();
  await revision.getByLabel('Revise this answer').fill('Only unmatched imported amounts need approval.');
  await revision.getByRole('button', { name: 'Save revision' }).click();
  await expect(revision.getByRole('complementary').getByText('Only unmatched imported amounts need approval.')).toBeVisible();
  await request.post(`/test/runtime/run-once?sessionId=${revisionData.session_id}`);
  await revision.reload();
  await expect(revision.getByRole('heading', { name: 'Ready to finish?' })).toBeVisible();
  await revision.close();

  const conflictData = await fixture(request, 'conflict@example.com');
  const conflict = await browser.newPage();
  await begin(conflict, conflictData);
  await conflict.getByLabel('Your answer in your own words').fill('Every mismatch needs approval.');
  await conflict.getByRole('button', { name: 'Submit answer' }).click();
  await request.post(`/test/runtime/run-once?sessionId=${conflictData.session_id}`);
  await conflict.reload();
  await conflict.getByLabel('Your answer in your own words').fill('This contradicts my first answer: routine mismatches do not need approval.');
  await conflict.getByRole('button', { name: 'Submit answer' }).click();
  await request.post(`/test/runtime/run-once?sessionId=${conflictData.session_id}`);
  await conflict.reload();
  await expect(conflict.getByText('Contradictory evidence needs review')).toBeVisible();
  await conflict.getByRole('button', { name: 'Confirm and finish' }).click();
  await request.post('/test/extraction/run-once');
  await conflict.close();

  const conflictReview = await browser.newPage();
  await signIn(conflictReview);
  await conflictReview.goto(`/app/findings/${conflictData.session_id}`);
  await expect(conflictReview.getByText('1 of 1 required areas have an explicit outcome')).toBeVisible();
  await expect(conflictReview.getByText('Conflict members · 2')).toBeVisible();
  for (const button of await conflictReview.getByRole('button', { name: 'Accept', exact: true }).all()) await button.click();
  await conflictReview.getByRole('button', { name: 'Acknowledge unresolved' }).click();
  await conflictReview.getByRole('button', { name: 'Accept exact package' }).click();
  await conflictReview.close();

  const pauseData = await fixture(request, 'pause@example.com');
  const pause = await browser.newPage();
  await begin(pause, pauseData);
  await request.post(`/test/sessions/${pauseData.session_id}/near-limit`);
  await pause.reload();
  await expect(pause.getByRole('heading', { name: 'Nearly at the expected time' })).toBeVisible();
  await pause.getByRole('button', { name: 'Keep going for 10 minutes' }).click();
  await expect(pause.getByRole('heading', { name: 'Nearly at the expected time' })).not.toBeVisible();
  await pause.getByRole('button', { name: 'Pause and leave' }).click();
  await expect(pause.getByRole('heading', { name: 'Your place is saved' })).toBeVisible();
  await pause.getByRole('button', { name: 'Resume interview' }).click();
  await expect(pause.getByRole('heading', { name: 'Walk me through the last mismatch you resolved.' })).toBeVisible();
  await pause.getByRole('button', { name: 'End here' }).click();
  await expect(pause.getByRole('heading', { name: 'Thank you' })).toBeVisible();
  await pause.close();

  const review = await browser.newPage();
  await signIn(review);
  await review.goto(`/app/findings/${unknownData.session_id}`);
  await expect(review.getByText('UNKNOWN', { exact: true })).toBeVisible();
  await review.getByLabel('Required follow-up').fill('Ask the reconciliation owner.');
  await review.getByRole('button', { name: 'Reject package' }).click();
  await expect(review.getByText('Decision · REJECTED')).toBeVisible();
  await expect(review.getByText('Ask the reconciliation owner.')).toBeVisible();
  await expect(review.getByText('Package Version 1')).toBeVisible();
  await review.getByRole('button', { name: 'Delete Interview Session' }).click();
  const deleted = await review.goto(`/app/findings/${unknownData.session_id}`);
  expect(deleted?.status()).toBe(404);
  await review.close();
});

test('expired and reused invitation plus runtime failure expose safe recovery', async ({ browser, request }) => {
  const expired = await fixture(request, 'expired@example.com');
  await request.post(`/test/invitations/${expired.invitation_id}/expire`);
  const expiredPage = await browser.newPage();
  await expiredPage.goto(expired.invitation_url);
  await expect(expiredPage.getByText('Ask Gareth to reissue access')).toBeVisible();
  await expiredPage.close();

  const failed = await fixture(request, 'runtime-failed@example.com');
  const page = await browser.newPage();
  await begin(page, failed);
  await page.getByLabel('Your answer in your own words').fill('This accepted answer must remain visible.');
  await page.getByRole('button', { name: 'Submit answer' }).click();
  await request.post('/test/faults/runtime', { form: { remaining: '3' } });
  await request.post(`/test/runtime/run-once?sessionId=${failed.session_id}`);
  await request.post(`/test/runtime/run-once?sessionId=${failed.session_id}`);
  await request.post(`/test/runtime/run-once?sessionId=${failed.session_id}`);
  await page.reload();
  await expect(page.getByText('Your answer is safe')).toBeVisible();
  await expect(page.getByText('do not need to repeat')).toBeVisible();
  await expect(page.getByText('This accepted answer must remain visible.')).toBeVisible();
  await page.getByRole('button', { name: 'Try again' }).click();
  await request.post(`/test/runtime/run-once?sessionId=${failed.session_id}`);
  await page.reload();
  await expect(page.getByRole('heading', { name: 'What happens next after that?' })).toBeVisible();
  await page.close();

  const extraction = await fixture(request, 'extraction-failed@example.com');
  const interviewee = await browser.newPage();
  await begin(interviewee, extraction);
  await interviewee.getByRole('button', { name: 'I don’t know' }).click();
  await interviewee.getByRole('button', { name: 'Confirm and finish' }).click();
  await request.post('/test/faults/extraction', { form: { remaining: '1' } });
  await request.post('/test/extraction/run-once');
  const investigator = await browser.newPage();
  await signIn(investigator);
  await investigator.goto(`/app/findings/${extraction.session_id}`);
  await expect(investigator.getByText('Extraction failed · Retry is safe')).toBeVisible();
  await investigator.getByRole('button', { name: 'Retry extraction' }).click();
  await request.post('/test/extraction/run-once');
  await investigator.reload();
  await expect(investigator.getByText('Ready for review')).toBeVisible();
  await interviewee.close();
  await investigator.close();
});

test('lost browser, reissue, revocation, and termination invalidate stale participant access', async ({ browser, request }) => {
  const data = await fixture(request, 'access-recovery@example.com');
  const firstBrowser = await browser.newPage();
  await firstBrowser.goto(data.invitation_url);
  await firstBrowser.getByRole('button', { name: 'Continue to invitation' }).click();
  await expect(firstBrowser.getByRole('heading', { name: 'Before you begin' })).toBeVisible();

  const lostBrowser = await browser.newPage();
  await lostBrowser.goto(data.invitation_url);
  await expect(lostBrowser.getByText('Ask Gareth to reissue access')).toBeVisible();
  await lostBrowser.close();

  const investigator = await browser.newPage();
  await signIn(investigator);
  await investigator.goto(data.review_url);
  await investigator.getByLabel('Name').fill('Clare');
  await investigator.getByLabel('Email').fill('access-recovery@example.com');
  await investigator.getByRole('button', { name: 'Send invitation' }).click();
  expect((await firstBrowser.reload())?.status()).toBe(403);

  const email = await (await request.get('/test/emails/latest?recipient=access-recovery%40example.com')).json();
  expect(email.link).not.toBe(data.invitation_url);
  const recovered = await browser.newPage();
  await recovered.goto(email.link);
  await recovered.getByRole('button', { name: 'Continue to invitation' }).click();
  await expect(recovered.getByRole('heading', { name: 'Before you begin' })).toBeVisible();
  await recovered.getByRole('button', { name: 'Begin interview' }).click();
  await expect(recovered.getByRole('heading', { name: 'Walk me through the last mismatch you resolved.' })).toBeVisible();

  const termination = await request.post(`/operator/sessions/${data.session_id}/terminate`, {
    headers: { 'X-Operator-Token': 'test-operator-token' },
  });
  expect(termination.ok()).toBeTruthy();
  expect((await recovered.reload())?.status()).toBe(403);
  await recovered.close();
  await firstBrowser.close();
  await investigator.close();

  const revoked = await fixture(request, 'revoked-link@example.com');
  const revocation = await request.post(`/operator/invitations/${revoked.invitation_id}/revoke`, {
    headers: { 'X-Operator-Token': 'test-operator-token' },
  });
  expect(revocation.ok()).toBeTruthy();
  const revokedPage = await browser.newPage();
  await revokedPage.goto(revoked.invitation_url);
  await expect(revokedPage.getByText('Ask Gareth to reissue access')).toBeVisible();
  await revokedPage.close();
});

test('retention warning and Discovery deletion deny access immediately', async ({ page, request }) => {
  const data = await fixture(request, 'retention@example.com');
  await request.post(`/test/discoveries/${data.discovery_id}/retention-warning`);
  await signIn(page);
  await page.goto(`/app/discoveries/${data.discovery_id}`);
  await expect(page.getByText('Scheduled deletion is within 14 days')).toBeVisible();
  await page.getByRole('button', { name: 'Delete Discovery' }).click();
  await expect(page.getByRole('heading', { name: 'Your Discoveries' })).toBeVisible();
  const participant = await browserPageFromInvitation(page.context().browser(), data.invitation_url);
  await expect(participant.getByText('Ask Gareth to reissue access')).toBeVisible();
  await participant.close();
});

test('Interview Session deletion removes findings and participant access immediately', async ({ browser, page, request }) => {
  const data = await fixture(request, 'session-deletion@example.com');
  const participant = await browser.newPage();
  await begin(participant, data);
  await participant.getByLabel('Your answer in your own words').fill('The analyst checks unmatched entries against the source ledger.');
  await participant.getByRole('button', { name: 'Submit answer' }).click();
  await request.post(`/test/runtime/run-once?sessionId=${data.session_id}`);
  await participant.reload();
  await participant.getByLabel('Yes').check();
  await participant.getByRole('button', { name: 'Submit response' }).click();
  await request.post(`/test/runtime/run-once?sessionId=${data.session_id}`);
  await participant.reload();
  await participant.getByRole('button', { name: 'Confirm and finish' }).click();
  await request.post('/test/extraction/run-once');

  await signIn(page);
  await page.goto(`/app/findings/${data.session_id}`);
  await page.getByRole('button', { name: 'Delete Interview Session' }).click();
  await expect(page.getByRole('heading', { name: 'Your Discoveries' })).toBeVisible();
  expect((await page.goto(`/app/findings/${data.session_id}`))?.status()).toBe(404);
  await participant.reload();
  await expect(participant.getByRole('heading', { name: 'Access is no longer available' })).toBeVisible();
  await participant.close();
});

const browserPageFromInvitation = async (browser, url: string) => {
  const page = await browser.newPage();
  await page.goto(url);
  return page;
};
