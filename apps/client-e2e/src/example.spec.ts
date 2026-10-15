import { test, expect } from '@playwright/test';

test('shows the public directory landing page', async ({ page }) => {
  await page.goto('/');

  await expect(
    page.getByRole('heading', {
      name: 'Find a church. Understand what it believes.',
    }),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', { name: 'Church Directory' }),
  ).toBeVisible();
});
