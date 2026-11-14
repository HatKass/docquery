import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { DEMO_WORKSPACE_ID, OWNER, VIEWER, callApi, expectNoHorizontalScroll, signIn } from './support/stack';

/** WCAG 2.1 A/AA rules; serious and critical violations fail the test (PROJECT_SPEC §10). */
async function expectNoSeriousAccessibilityViolations(page: Page): Promise<void> {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze();
  const serious = results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map(
      (v) =>
        `${v.id} (${v.impact}): ${v.help}\n` +
        v.nodes.map((n) => `  ${n.html.slice(0, 160)}\n    ${(n.failureSummary ?? '').replace(/\n/g, ' ')}`).join('\n'),
    );
  expect(serious, serious.join('\n')).toEqual([]);
}

test.describe('roles', () => {
  test('a viewer can read but not upload or delete', async ({ browser }) => {
    // Its own workspace with a known VIEWER, whatever the local demo data looks like.
    const ownerPage = await (await browser.newContext()).newPage();
    await signIn(ownerPage, OWNER);
    const created = await callApi(ownerPage, 'POST', '/api/workspaces', { name: `e2e roles ${Date.now()}` });
    const workspaceId = (await created.json()).id as string;
    try {
      await callApi(ownerPage, 'POST', `/api/workspaces/${workspaceId}/members`, {
        usernameOrEmail: VIEWER.username,
        role: 'VIEWER',
      });
      const viewerPage = await (await browser.newContext()).newPage();
      await signIn(viewerPage, VIEWER);
      await viewerPage.goto(`/workspaces/${workspaceId}`);

      await expect(viewerPage.getByTestId('read-only-hint')).toBeVisible();
      await expect(viewerPage.getByTestId('file-input')).toHaveCount(0);
      await expect(viewerPage.getByTestId('delete-document')).toHaveCount(0);
      await viewerPage.context().close();
    } finally {
      await callApi(ownerPage, 'DELETE', `/api/workspaces/${workspaceId}`);
      await ownerPage.context().close();
    }
  });
});

test.describe('layout and accessibility', () => {
  test('home page @responsive', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('sign-in')).toBeVisible();
    await expectNoHorizontalScroll(page);
    await expectNoSeriousAccessibilityViolations(page);
  });

  test('workspace pages @responsive', async ({ page }) => {
    await signIn(page, OWNER);
    // Each page once its data is shown (not "network idle": the library keeps a live status stream open).
    const pages: [string, string][] = [
      ['/workspaces', 'workspace-list'],
      [`/workspaces/${DEMO_WORKSPACE_ID}`, 'document-table'],
      [`/workspaces/${DEMO_WORKSPACE_ID}/chat`, 'question-input'],
    ];
    for (const [path, content] of pages) {
      await page.goto(path);
      await expect(page.getByTestId(content)).toBeVisible();
      await expectNoHorizontalScroll(page);
      await expectNoSeriousAccessibilityViolations(page);
    }
  });

  test('the navigation opens from the menu button on a phone @responsive', async ({ page, isMobile }) => {
    test.skip(!isMobile, 'phones only: desktops show the sidebar');
    await signIn(page, OWNER);
    await page.goto(`/workspaces/${DEMO_WORKSPACE_ID}`);

    await page.getByTestId('menu-button').click();
    const nav = page.getByTestId('mobile-nav');
    await expect(nav).toBeVisible();
    await nav.getByRole('link', { name: /Ask/ }).click();
    await expect(page).toHaveURL(new RegExp(`/workspaces/${DEMO_WORKSPACE_ID}/chat$`));
    await expect(nav).toBeHidden();
  });
});
