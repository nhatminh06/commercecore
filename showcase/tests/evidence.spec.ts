import { test, expect } from '@playwright/test';

const examples = ['inventory', 'checkout', 'synthetic'];
for (const width of [390, 768, 1440]) {
  for (const id of examples) {
    test(`${id}: inspector, keyboard, zoom, scroll and print at ${width}px`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 });
      const requests: string[] = [];
      const errors: string[] = [];
      page.on('request', r => requests.push(r.url()));
      page.on('pageerror', e => errors.push(e.message));
      page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
      const response = await page.goto(`/reports/${id}`);
      expect(response?.status()).toBe(200);
      expect(response?.headers()['content-security-policy']).toContain("script-src 'self'");
      await expect(page.locator('#verdict')).toContainText(id === 'synthetic' ? 'FAIL' : 'PASS');
      await expect(page.locator('#meta')).toContainText(id === 'synthetic' ? 'Synthetic mutation' : 'Captured test execution');
      await expect(page.locator('.note').first()).toContainText('deployment approval pending');
      await expect(page.locator('.call')).toHaveCount(id === 'checkout' ? 6 : 4);
      const operation = page.locator('.call').nth(1);
      const identity = await operation.getAttribute('data-id');
      await operation.focus();
      await page.keyboard.press('Space');
      await expect(page.locator('#details h3')).toHaveText(identity!);
      await expect(operation).toHaveAttribute('aria-pressed', 'true');
      expect(await operation.evaluate(el => getComputedStyle(el).outlineStyle)).toBe('solid');
      const before = await operation.evaluate(el => (el as HTMLElement).style.left);
      await page.getByRole('button', { name: 'Zoom in', exact: true }).click();
      expect(await page.locator('.call').nth(1).evaluate(el => (el as HTMLElement).style.left)).not.toBe(before);
      const timeline = page.locator('#timeline');
      expect(await timeline.evaluate(el => el.scrollWidth > el.clientWidth)).toBe(true);
      await timeline.evaluate(el => { el.scrollLeft = 200; });
      expect(await timeline.evaluate(el => el.scrollLeft)).toBeGreaterThan(0);
      await page.getByRole('button', { name: 'Zoom out', exact: true }).click();
      if (id === 'synthetic') {
        await expect(page.getByRole('heading', { name: 'Synthetic operation intervals', exact: true })).toBeVisible();
        await expect(page.locator('#details')).toContainText('no actual service call was executed');
        await expect(page.locator('.sub')).toContainText('Horizontal positions represent constructed intervals');
        await expect(page.locator('.sub')).not.toContainText('Horizontal position is the M18 test JVM monotonic clock');
        await expect(page.locator('#meta')).toContainText('Inherited fixture metadata; synthetic intervals are constructed, not measured JVM timing.');
        await expect(page.locator('#meta')).toContainText('Inherited fixture metadata; does not describe an observed execution of this synthetic history.');
        await expect(page.locator('#explanation')).toContainText('Inherited checker-contract scope label; does not mean these synthetic calls occurred in CommerceCore.');
        await expect(page.locator('#comparison')).toContainText('not an observed CommerceCore failure');
        await page.getByRole('tab', { name: 'Reduced · analytical', exact: true }).focus();
        await page.keyboard.press('Enter');
        await expect(page.locator('.call')).toHaveCount(2);
        await expect(page.locator('#view-note')).toContainText('Analytically reduced Synthetic mutation');
        await page.getByRole('tab', { name: 'Original', exact: true }).click();
        await expect(page.locator('.call')).toHaveCount(4);
      } else {
        const witness = page.locator('#witness button').last();
        await witness.click();
        await expect(page.locator('#details h3')).toHaveText((await witness.textContent())!);
        await expect(page.locator('#witness')).toContainText('not an observed execution order');
      }
      await page.evaluate(() => window.scrollTo(0, 0));
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
      await page.screenshot({ path: `test-results/report-${id}-${width}.png`, fullPage: true });
      if (width === 1440) {
        await page.emulateMedia({ media: 'print' });
        expect(await timeline.evaluate(el => getComputedStyle(el).overflowX)).toBe('visible');
        await page.pdf({ path: `test-results/report-${id}.pdf`, format: 'A4', printBackground: true });
      }
      expect((await page.reload())?.status()).toBe(200);
      await expect(page.locator('#verdict')).toContainText(id === 'synthetic' ? 'FAIL' : 'PASS');
      expect(requests.every(url => /^http:\/\/127\.0\.0\.1:4173\/reports\//.test(url))).toBe(true);
      expect(errors).toEqual([]);
    });
  }
}

test('lab navigation, report selection, provenance and true 404', async ({ page, request }) => {
  await page.goto('/lab');
  await expect(page.getByRole('list', { name: 'Evidence pipeline' })).toBeVisible();
  await page.getByRole('link', { name: 'Choose a debugger report' }).click();
  await page.getByLabel('Evidence example', { exact: true }).selectOption('synthetic');
  await expect(page.getByText('Synthetic mutation — not an observed application defect', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Open synthetic oversell counterexample report', exact: true }).click();
  await expect(page.locator('#meta')).toContainText('Synthetic mutation');
  await page.goto('/evidence');
  await expect(page.getByRole('heading', { name: 'Historical backend result' })).toBeVisible();
  const synthetic = page.locator('section').filter({ has: page.locator('#evidence-synthetic') });
  await expect(synthetic).toContainText('Inherited fixture topology');
  await expect(synthetic).toContainText('does not describe an observed execution of this synthetic history.');
  await expect(page.getByText('Capture Git revisions are UNVERIFIED.', { exact: false })).toBeVisible();
  expect((await request.get('/reports/missing')).status()).toBe(404);
});
