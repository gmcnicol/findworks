import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

// Typography contract against the actual stylesheet; not a live-interview acceptance test.
test('interview questions use readable text sizing rather than display sizing', async ({ page }) => {
  const css = readFileSync('src/main/resources/static/assets/app.css', 'utf8');
  for (const [width, height, size] of [[1360, 900, '20px'], [390, 844, '18px']] as const) {
    await page.setViewportSize({ width, height });
    await page.setContent(`<html><body class="participant-body"><main class="interview-shell">
      <header class="participant-masthead"><span class="participant-brand">FindWorks</span></header>
      <section class="interview-stage"><h1 class="interview-question" id="active-question">
        Think of a recent piece of client work that was difficult to keep on track. What happened, and how did your team handle it?
      </h1><form class="form answer-form"><label>Your answer in your own words<textarea rows="7"></textarea></label>
        <button class="button">Submit answer</button></form></section></main></body></html>`);
    await page.addStyleTag({ content: css });
    await expect(page.locator('#active-question')).toHaveCSS('font-size', size);
    await expect(page.locator('#active-question')).toHaveCSS('line-height', width > 600 ? '31px' : '27.9px');
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await expect(page.getByRole('button', { name: 'Submit answer' })).toBeInViewport();
  }
});
