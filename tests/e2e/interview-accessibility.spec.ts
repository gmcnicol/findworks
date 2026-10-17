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
};

type AxNode = {
  ignored: boolean;
  role?: { value?: unknown };
  name?: { value?: unknown };
  properties?: Array<{ name: string; value: { value?: unknown } }>;
};

const axValue = (node: AxNode, property: string) =>
  node.properties?.find((candidate) => candidate.name === property)?.value.value;

const accessibilityTree = async (page: Page) => {
  const session = await page.context().newCDPSession(page);
  try {
    const { nodes } = await session.send('Accessibility.getFullAXTree');
    return nodes as AxNode[];
  } finally {
    await session.detach();
  }
};

const expectAccessibleState = async (page: Page, heading: string) => {
  const focusTarget = page.locator('#interview-shell [data-focus-target]');
  await expect(focusTarget).toHaveCount(1);
  await expect(focusTarget).toHaveAccessibleName(heading);
  await expect(focusTarget).toBeFocused();

  await expect.poll(async () => {
    const nodes = (await accessibilityTree(page)).filter((node) => !node.ignored);
    return nodes
      .filter((node) => node.role?.value === 'heading' && axValue(node, 'focused') === true)
      .map((node) => node.name?.value);
  }).toEqual([heading]);

  const nodes = (await accessibilityTree(page)).filter((node) => !node.ignored);
  const liveAnnouncementSources = nodes.filter((node) => {
    const live = axValue(node, 'live');
    return live === 'polite' || live === 'assertive';
  });
  expect(liveAnnouncementSources.length).toBeLessThanOrEqual(1);
  expect(liveAnnouncementSources.map((node) => node.name?.value).filter(Boolean)).not.toContain(heading);
  expect(nodes.filter((node) => node.role?.value === 'complementary')).toHaveLength(0);

  const unnamedControls = nodes.filter((node) =>
    ['button', 'DisclosureTriangle', 'link', 'radio', 'textbox'].includes(String(node.role?.value))
      && !String(node.name?.value ?? '').trim());
  expect(unnamedControls).toEqual([]);
  expect(nodes.filter((node) => node.role?.value === 'log')).toHaveLength(1);
};

const expectControl = async (
  page: Page,
  role: string,
  name: string,
  states: Record<string, unknown> = {},
) => {
  const matches = (await accessibilityTree(page)).filter((node) =>
    !node.ignored && node.role?.value === role && node.name?.value === name);
  expect(matches, `${role} “${name}” should occur exactly once in the Chromium accessibility tree`).toHaveLength(1);
  for (const [state, expected] of Object.entries(states)) {
    expect(axValue(matches[0], state), `${role} “${name}” should expose ${state}=${String(expected)}`).toBe(expected);
  }
};

test('accessibility tree exposes one announced surface from initial Question through working to the next Question', async ({ page, request }) => {
  const data = await fixture(request, 'ax-question-journey@example.com');
  await begin(page, data);

  const initialQuestion = 'Walk me through the last mismatch you resolved.';
  await expectAccessibleState(page, initialQuestion);
  await expectControl(page, 'textbox', 'Your answer in your own words', { disabled: undefined, required: true });
  await expectControl(page, 'button', 'Submit answer', { disabled: undefined });
  await expectControl(page, 'button', 'I don’t know', { disabled: undefined });
  await expectControl(page, 'button', 'Please clarify', { disabled: undefined });
  await expectControl(page, 'button', 'Pause and leave', { disabled: undefined });
  await expectControl(page, 'button', 'This feels uncomfortable', { disabled: undefined });
  await expectControl(page, 'button', 'End here', { disabled: undefined });
  await expectControl(page, 'DisclosureTriangle', 'Someone else owns this', { expanded: false });

  await page.getByLabel('Your answer in your own words').fill('The finance analyst checks the imported amount against the source ledger.');
  await page.evaluate(() => document.addEventListener('submit', (event) => event.preventDefault(), {
    capture: true,
    once: true,
  }));
  await page.getByRole('button', { name: 'Submit answer' }).click();
  await expect(page.getByRole('button', { name: 'Sending…' })).toHaveAttribute('aria-disabled', 'true');
  await expect(page.getByRole('status')).toHaveText('Waiting for FindWorks to confirm…');
  const exposedSubmittingNodes = (await accessibilityTree(page)).filter((node) => !node.ignored);
  expect(exposedSubmittingNodes.filter((node) => {
    const live = axValue(node, 'live');
    return live === 'polite' || live === 'assertive';
  })).toHaveLength(1);
  expect(exposedSubmittingNodes
    .filter((node) => ['button', 'heading'].includes(String(node.role?.value)) && axValue(node, 'focused') === true)
    .map((node) => [node.role?.value, node.name?.value])).toEqual([['button', 'Sending…']]);
  const submittingButton = exposedSubmittingNodes.filter((node) =>
    node.role?.value === 'button' && node.name?.value === 'Sending…');
  expect(submittingButton).toHaveLength(1);
  expect(axValue(submittingButton[0], 'disabled')).toBe(true);

  await page.reload();
  await expect(page.getByLabel('Your answer in your own words')).toHaveValue('The finance analyst checks the imported amount against the source ledger.');
  await page.getByRole('button', { name: 'Submit answer' }).click();
  await expectAccessibleState(page, 'Preparing your next question');
  await expect(page.locator('textarea')).toHaveCount(0);

  await request.post(`/test/runtime/run-once?sessionId=${data.session_id}`);
  const nextQuestion = 'What does the finance analyst check in the source ledger?';
  await expectAccessibleState(page, nextQuestion);
  for (const option of ['Yes', 'No', 'Partly']) {
    await expectControl(page, 'radio', option, { checked: 'false' });
  }
  await expectControl(page, 'textbox', 'Anything to add?');
  await expectControl(page, 'button', 'Submit response');
  await expect(page.getByRole('complementary')).toHaveCount(0);
});

test('accessibility tree keeps lifecycle states singular and revision mode exposes one editor', async ({ page, request }) => {
  const lifecycleStates = [
    {
      state: 'PAUSED',
      heading: 'Your place is saved',
      controls: [
        { role: 'button', name: 'Resume interview' },
        { role: 'button', name: 'End here' },
      ],
    },
    {
      state: 'RUNTIME_FAILED',
      heading: 'We couldn’t prepare the next question',
      controls: [
        { role: 'button', name: 'Try again' },
        { role: 'button', name: 'End the interview here' },
      ],
    },
    {
      state: 'AWAITING_CONFIRMATION',
      heading: 'Ready to finish?',
      controls: [{ role: 'button', name: 'Confirm and finish' }],
    },
    {
      state: 'COMPLETED',
      heading: 'Thank you',
      controls: [],
    },
  ] as const;

  for (const lifecycle of lifecycleStates) {
    const data = await fixture(request, `ax-${lifecycle.state.toLowerCase()}@example.com`);
    await begin(page, data);
    expect((await request.post(`/test/interviews/${data.session_id}/long-conversation`)).ok()).toBeTruthy();
    expect((await request.post(`/test/interviews/${data.session_id}/state`, {
      form: { state: lifecycle.state },
    })).ok()).toBeTruthy();
    await page.reload();

    await expectAccessibleState(page, lifecycle.heading);
    for (const control of lifecycle.controls) {
      await expectControl(page, control.role, control.name, { disabled: undefined });
    }
    await expect(page.locator('textarea')).toHaveCount(0);
    await expect(page.getByRole('complementary')).toHaveCount(0);
  }

  const revision = await fixture(request, 'ax-single-revision-editor@example.com');
  await begin(page, revision);
  expect((await request.post(`/test/interviews/${revision.session_id}/long-conversation`)).ok()).toBeTruthy();
  await page.reload();
  await page.getByRole('link', { name: 'Correct this response' }).first().click();

  await expectAccessibleState(page, 'Correct your saved response');
  await expectControl(page, 'textbox', 'Add a correction', { required: true });
  await expectControl(page, 'button', 'Append correction', { disabled: undefined });
  await expectControl(page, 'link', 'Cancel correction');
  await expect(page.locator('form[data-revision-form]')).toHaveCount(1);
  await expect(page.locator('form[data-answer-form]')).toHaveCount(0);
  await expect(page.locator('textarea:visible')).toHaveCount(1);
  await expect(page.getByRole('textbox', { name: 'Your answer in your own words' })).toHaveCount(0);
  await expect(page.getByRole('textbox', { name: 'Anything to add?' })).toHaveCount(0);
  await expect(page.getByRole('complementary')).toHaveCount(0);
});
