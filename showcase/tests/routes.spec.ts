import { test, expect } from '@playwright/test';

const routes = [
  ['/', 'Overview'], ['/walkthrough', 'Simulated checkout'],
  ['/architecture', 'System architecture'], ['/lab', 'Consistency Lab'],
  ['/lab/debugger', 'Execution-history debugger'], ['/evidence', 'Evidence and reproduction'],
];

for (const [path, title] of routes) {
  test(`direct load, refresh, and network isolation: ${path}`, async ({ page }) => {
    const requests: string[] = [];
    const errors: string[] = [];
    page.on('request', request => requests.push(request.url()));
    page.on('pageerror', error => errors.push(error.message));
    const response = await page.goto(path);
    expect(response?.status()).toBe(200);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(title);
    await expect(page.getByRole('navigation', { name: 'Primary navigation' }).getByRole('link')).toHaveCount(6);
    await expect(page.locator('[aria-current="page"]')).toHaveAttribute('href', path);
    expect((await page.reload())?.status()).toBe(200);
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(title);
    expect(errors).toEqual([]);
    expect(requests.every(url => url.startsWith('http://127.0.0.1:4173/'))).toBe(true);
    expect(requests.some(url => /\/api\/|:8080|:8091/.test(url))).toBe(false);
    expect(response?.headers()['content-security-policy']).toContain("connect-src 'none'");
  });
}

test('unknown routes and missing assets return HTTP 404', async ({ page, request }) => {
  for (const path of ['/missing', '/lab/missing', '/assets/missing.js', '/api/commercecore/products']) {
    expect((await request.get(path)).status()).toBe(404);
  }
  expect((await page.goto('/missing'))?.status()).toBe(404);
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Page not found');
});

test('keyboard navigation and desktop/mobile layout', async ({ page }) => {
  await page.goto('/');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
  expect(await page.locator(':focus').evaluate(el => getComputedStyle(el).outlineStyle)).toBe('solid');
  await page.keyboard.press('Enter');
  await expect(page.locator('main')).toBeFocused();
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 900 });
    await page.reload();
    await expect(page.getByRole('navigation', { name: 'Primary navigation' })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `test-results/overview-${width}.png`, fullPage: true });
  }
  await page.getByRole('navigation', { name: 'Primary navigation' }).getByRole('link', { name: 'Walkthrough', exact: true }).click();
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('Simulated checkout');
  await expect(page.getByText('Educational model only.', { exact: false })).toBeVisible();
});
