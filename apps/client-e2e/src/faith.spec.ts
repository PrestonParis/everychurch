import { test, expect } from '@playwright/test';

// Only run with faith.playwright.config.ts against the isolated API described in docs/FAITH_MATCHING.md.
test('anonymous visitor completes quiz and explores sourced, uncertain results on mobile', async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/learn');
  await expect(
    page.getByRole('heading', { name: /A place to explore/ }),
  ).toBeVisible();
  await page.screenshot({ path: '/tmp/everychurch-learn-mobile.png' });
  await page.getByRole('button', { name: 'Menu', exact: true }).click();
  await expect(
    page.getByRole('menuitem', { name: 'Learn', exact: true }),
  ).toBeVisible();
  await page.getByRole('menuitem', { name: 'Learn', exact: true }).click();
  await page.getByRole('link', { name: /Take the denomination quiz/ }).click();
  await expect(page.getByText('Question 1 of 24')).toBeVisible();
  await page
    .getByRole('radio', { name: /One God in three distinct persons/ })
    .check();
  await page
    .getByRole('combobox')
    .selectOption({ label: 'Essential — flag any difference' });
  for (let i = 0; i < 23; i++)
    await page.getByRole('button', { name: 'Next →', exact: true }).click();
  await page.getByRole('button', { name: 'See my results' }).click();
  await expect(
    page.getByRole('heading', { name: 'Your results' }),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', {
      name: 'How you compare with major traditions',
    }),
  ).toBeVisible();
  await expect(page.locator('app-faith-match').first()).toContainText(
    'Based on',
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  expect(await page.evaluate(() => Object.keys(localStorage).length)).toBe(0);
  await page.screenshot({
    path: '/tmp/everychurch-faith-mobile.png',
    fullPage: true,
  });
  await page.getByRole('button', { name: 'Start over' }).click();
  await page.getByRole('link', { name: 'Your results' }).click();
  await expect(
    page.getByText('Take the quiz to see how your beliefs compare'),
  ).toBeVisible();
});

test('church owner publishes beliefs, visitor matches, and saved results are private', async ({
  page,
  request,
  browser,
}) => {
  const email = `faith-owner-${Date.now()}@example.test`,
    password = 'faith-test-password-123';
  const registration = await request.post('/api/auth/register', {
    data: { email, password, displayName: 'Faith Owner' },
  });
  expect(registration.ok()).toBeTruthy();
  const owner = await registration.json(),
    headers = { Authorization: `Bearer ${owner.token}` };
  const created = await request.post('/api/churches', {
    headers,
    data: { name: 'Faith E2E Parish', city: 'Faithville', state: 'IL' },
  });
  expect(created.ok()).toBeTruthy();
  const church = await created.json();
  const claim = await request.post(`/api/churches/${church.id}/claims`, {
    headers,
    data: { notes: 'Isolated test fixture' },
  });
  expect(claim.ok()).toBeTruthy();
  const adminResponse = await request.post('/api/auth/login', {
    data: {
      email: 'faith-admin@example.test',
      password: 'faith-test-admin-123',
    },
  });
  expect(adminResponse.ok()).toBeTruthy();
  const admin = await adminResponse.json();
  const approval = await request.post(
    `/api/admin/claims/${(await claim.json()).id}/approve`,
    { headers: { Authorization: `Bearer ${admin.token}` } },
  );
  expect(approval.ok()).toBeTruthy();
  await page.goto('/login');
  await page.locator('input[formControlName="email"]').fill(email);
  await page.locator('input[formControlName="password"]').fill(password);
  await page.getByRole('button', { name: /Log in/i }).click();
  await expect(page).toHaveURL(/churches/);
  await page.goto(`/churches/${church.id}/beliefs/edit`);
  await page
    .getByLabel('Optional reference tradition')
    .selectOption('catholic');
  await page
    .getByRole('button', { name: 'Fill unanswered topics from this reference' })
    .click();
  await page
    .getByLabel('Visitor participation guidance')
    .fill('Please speak with our priest before receiving Communion.');
  await page.getByRole('button', { name: 'Save draft', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('Draft saved');
  const publicDraft = await request.get(`/api/churches/${church.id}/beliefs`);
  expect((await publicDraft.json()).content).toBeNull();
  await page.getByRole('checkbox', { name: /I have reviewed/ }).check();
  await page.getByRole('button', { name: 'Publish confirmed profile' }).click();
  await expect(page.getByRole('status')).toContainText('now published');
  await page.goto('/learn/denomination-quiz');
  await page
    .getByRole('radio', { name: /One God in three distinct persons/ })
    .check();
  for (let i = 0; i < 23; i++)
    await page.getByRole('button', { name: 'Next →', exact: true }).click();
  await page.getByRole('button', { name: 'See my results' }).click();
  await page.getByLabel('City', { exact: true }).fill('Faithville');
  await page.getByLabel('State (for example, IL)').fill('IL');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(
    page.getByRole('link', { name: 'Faith E2E Parish →' }).first(),
  ).toBeVisible();
  await expect(
    page
      .getByText('Please speak with our priest before receiving Communion.')
      .first(),
  ).toBeVisible();
  await page
    .getByRole('button', { name: 'Save to my account', exact: true })
    .click();
  await expect(page.getByRole('status')).toContainText('Saved privately');
  const anonymous = await browser.newContext();
  const outsider = await anonymous.request.get(
    'http://127.0.0.1:4305/api/users/me/quiz-attempts',
  );
  expect(outsider.status()).toBe(401);
  await anonymous.close();
  await page
    .getByRole('button', { name: 'Delete', exact: true })
    .first()
    .click();
  await expect(
    page.getByText('No saved results.', { exact: true }),
  ).toBeVisible();
});

test('platform editor publishes a new immutable quiz edition', async ({
  page,
  request,
}) => {
  await page.goto('/login');
  await page
    .locator('input[formControlName="email"]')
    .fill('faith-admin@example.test');
  await page
    .locator('input[formControlName="password"]')
    .fill('faith-test-admin-123');
  await page.getByRole('button', { name: /Log in/i }).click();
  await expect(page).toHaveURL(/churches/);
  await page.goto('/admin/faith');
  await expect(
    page.getByRole('heading', { name: 'Quiz & tradition content' }),
  ).toBeVisible();
  const edition = `e2e-${Date.now()}`;
  await page.getByLabel('New edition identifier').fill(edition);
  await page
    .getByRole('checkbox', { name: /I have reviewed this edition/ })
    .check();
  await page.getByRole('button', { name: 'Publish new edition' }).click();
  await expect(page.getByRole('status')).toContainText(`Published ${edition}`);
  expect((await (await request.get('/api/learn/quiz')).json()).version).toBe(
    edition,
  );
  expect(
    (await request.get('/api/learn/quiz/versions/2026-09-v1')).ok(),
  ).toBeTruthy();
});
