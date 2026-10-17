import { readFileSync } from 'node:fs';
import { test, expect } from '@playwright/test';

// Typography contract against the actual stylesheet; live transcript behavior is covered in interview-conversation.spec.ts.
test('interview questions and composer remain readable at mobile and widescreen sizes', async ({ page }) => {
  const css = readFileSync('src/main/resources/static/assets/app.css', 'utf8');
  for (const [width, height] of [[1440, 960], [390, 844]] as const) {
    await page.setViewportSize({ width, height });
    await page.setContent(`<html><body class="participant-body"><main class="interview-shell" id="interview-shell">
      <header class="participant-masthead"><span class="participant-brand">FindWorks</span></header>
      <section class="conversation-progress"><div class="progress-copy"><strong>Exploring 1 key area</strong><span>21 responses saved</span></div><progress value="0" max="100"></progress></section>
      <section class="conversation-current" data-active-question data-question-id="question-1">
        <article class="transcript-turn transcript-interviewer is-current"><p class="speaker-label">FindWorks interviewer</p>
          <h1 class="interview-question" id="active-question">Think of a recent piece of client work that was difficult to keep on track. What happened, and how did your team handle it?</h1>
        </article><form class="answer-composer"><label class="composer-label" for="answer">Your answer in your own words</label>
          <textarea id="answer" rows="5" placeholder="Write naturally"></textarea><div class="composer-footer"><span class="composer-hint">Ctrl + Enter to send</span><button class="button composer-send">Submit answer</button></div>
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
    const contrastRatios = await page.evaluate(() => {
      const channels = (value: string) => value.match(/[\d.]+/g)!.slice(0, 3).map(Number);
      const luminance = (value: string) => {
        const [red, green, blue] = channels(value).map((channel) => {
          const normalized = channel / 255;
          return normalized <= 0.04045 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4;
        });
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue;
      };
      const ratio = (foreground: string, background: string) => {
        const values = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
        return (values[0] + 0.05) / (values[1] + 0.05);
      };
      const paper = getComputedStyle(document.body).backgroundColor;
      const surface = getComputedStyle(document.querySelector('.answer-composer')!).backgroundColor;
      return {
        speaker: ratio(getComputedStyle(document.querySelector('.speaker-label')!).color, paper),
        progress: ratio(getComputedStyle(document.querySelector('.progress-copy span')!).color, paper),
        hint: ratio(getComputedStyle(document.querySelector('.composer-hint')!).color, surface),
        placeholder: ratio(getComputedStyle(document.querySelector('textarea')!, '::placeholder').color, surface),
      };
    });
    for (const value of Object.values(contrastRatios)) expect(value).toBeGreaterThanOrEqual(4.5);
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(width);
    await expect(page.getByRole('button', { name: 'Submit answer' })).toBeInViewport();
  }
});
