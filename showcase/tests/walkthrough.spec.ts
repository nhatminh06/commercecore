import { test, expect } from '@playwright/test';

for (const width of [390, 1440]) {
  test(`checkout, retry, lost response and reconciliation at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    const requests: string[] = [];
    const errors: string[] = [];
    page.on('request', r => requests.push(r.url()));
    page.on('pageerror', e => errors.push(e.message));
    await page.goto('/walkthrough');
    await page.getByLabel('Quantity: Field notebook').fill('2');
    await page.getByRole('button', { name: 'Checkout / retry same key', exact: true }).click();
    await expect(page.getByTestId('stock-NOTEBOOK')).toHaveText('Available: 3');
    await expect(page.locator('dd').filter({ hasText: /^SIM-ORDER-1 · PENDING$/ })).toBeVisible();
    await page.getByRole('button', { name: 'Checkout / retry same key', exact: true }).click();
    await expect(page.getByRole('status')).toContainText('Replayed SIM-ORDER-1');
    await expect(page.getByTestId('stock-NOTEBOOK')).toHaveText('Available: 3');
    await page.getByLabel('Payment scenario').selectOption('lost');
    await page.getByRole('button', { name: 'Simulate authorization', exact: true }).click();
    await expect(page.locator('dd').filter({ hasText: /^UNKNOWN$/ })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Simulate authorization', exact: true })).toBeDisabled();
    await page.getByRole('button', { name: 'Reconcile by provider lookup', exact: true }).click();
    await expect(page.locator('dd').filter({ hasText: /^AUTHORIZED$/ })).toBeVisible();
    await expect(page.getByText('Authorizations: 1 · Lookups: 1', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Apply simulated Kafka workflow', exact: true }).click();
    await expect(page.getByText('SIM-ORDER-1 · CONFIRMED', { exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.evaluate(() => window.scrollTo(0, 0));
    await page.screenshot({ path: `test-results/walkthrough-${width}.png`, fullPage: true });
    expect(requests.every(url => url.startsWith('http://127.0.0.1:4173/') && !url.includes('/api/'))).toBe(true);
    expect(errors).toEqual([]);
    await page.reload();
    await expect(page.getByRole('combobox', { name: 'Simulated order', exact: true })).toBeDisabled();
    await expect(page.getByTestId('stock-NOTEBOOK')).toHaveText('Available: 5');
  });
}

test('insufficient stock, cart conflict, expiry review and reset', async ({ page }) => {
  await page.goto('/walkthrough');
  await page.getByLabel('Quantity: Field notebook').fill('1');
  await page.getByLabel('Quantity: Workshop mug').fill('4');
  const checkout = page.getByRole('button', { name: 'Checkout / retry same key', exact: true });
  await checkout.click();
  await expect(page.getByRole('status')).toContainText('Insufficient stock');
  await expect(page.getByTestId('stock-NOTEBOOK')).toHaveText('Available: 5');
  await page.getByLabel('Quantity: Workshop mug').fill('0');
  await checkout.click();
  await page.getByLabel('Demo cart', { exact: true }).selectOption('cart-b');
  await checkout.click();
  await expect(page.getByRole('status')).toContainText('different cart');
  await page.getByLabel('Payment scenario').selectOption('lost');
  await page.getByRole('button', { name: 'Simulate authorization', exact: true }).click();
  await page.getByRole('button', { name: 'Illustrate reservation expiry', exact: true }).click();
  await page.getByRole('button', { name: 'Reconcile by provider lookup', exact: true }).click();
  await expect(page.getByText('REQUIRES_REVIEW', { exact: true })).toBeVisible();
  await expect(page.getByText('SIM-ORDER-1 · PENDING', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Apply simulated Kafka workflow', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Reset simulation', exact: true }).click();
  await expect(page.getByLabel('Idempotency key')).toHaveValue('demo-checkout-1');
  await expect(page.getByRole('combobox', { name: 'Simulated order', exact: true })).toBeDisabled();
});

test('checkout form works with keyboard and visible focus', async ({ page }) => {
  await page.goto('/walkthrough');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('link', { name: 'Skip to content' })).toBeFocused();
  await page.getByLabel('Quantity: Field notebook').focus();
  await page.keyboard.press('ArrowUp');
  await page.getByLabel('Idempotency key').focus();
  expect(await page.locator(':focus').evaluate(el => getComputedStyle(el).outlineStyle)).toBe('solid');
  await page.keyboard.press('Tab');
  await expect(page.getByRole('button', { name: 'Checkout / retry same key', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('status')).toContainText('Created SIM-ORDER-1');
  await page.emulateMedia({ reducedMotion: 'reduce' });
  expect(await page.getByRole('button', { name: 'Reset simulation' }).evaluate(el => getComputedStyle(el).transitionDuration)).toBe('0s');
});
