import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

// Typography contract against the actual stylesheet; live transcript behavior is covered in interview-conversation.spec.ts.
test('interview questions and composer remain readable at mobile and widescreen sizes', async ({ page }) => {
  const css = readFileSync('src/main/resources/static/assets/app.css', 'utf8');
  for (const [width, height] of [[1440, 960], [390, 844]] as const) {
    await page.setViewportSize({ width, height });
    await page.setContent(`<html><body class="participant-body"><main class="interview-shell" id="interview-shell">
      <header class="participant-masthead"><span class="participant-brand">FindWorks</span></header>
      <section class="conversation-current" data-active-question data-question-id="question-1">
        <article class="transcript-turn transcript-interviewer is-current"><p class="speaker-label">FindWorks interviewer</p>
          <h1 class="interview-question" id="active-question">Think of a recent piece of client work that was difficult to keep on track. What happened, and how did your team handle it?</h1>
        </article><form class="answer-composer"><label class="composer-label" for="answer">Your answer in your own words</label>
          <textarea id="answer" rows="5"></textarea><div class="composer-footer"><button class="button composer-send">Submit answer</button></div>
        </form></section></main></body></html>`);
    await page.addStyleTag({ content: css });
    const typography = await page.locator('#active-question').evaluate((question) => {
      const style = getComputedStyle(question);
      return { size: Number.parseFloat(style.fontSize), lineHeight: Number.parseFloat(style.lineHeight), width: question.getBoundingClientRect().width };
    });
    expect(typography.size).toBeGreaterThanOrEqual(18);
    expect(typography.size).toBeLessThanOrEqual(21);
    expect(typography.lineHeight / typography.size).toBeGreaterThanOrEqual(1.5);
    expect(typography.width).toBeLessThanOrEqual(700);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await expect(page.getByRole('button', { name: 'Submit answer' })).toBeInViewport();
  }
});
