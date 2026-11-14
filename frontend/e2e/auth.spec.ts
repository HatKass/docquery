import { expect, test } from '@playwright/test';
import { OWNER, signIn } from './support/stack';

test.describe('sign-in', () => {
  test('a signed-out visitor sees the sign-in page, not workspace data @responsive', async ({ page }) => {
    await page.goto('/workspaces');

    // The auth guard sends signed-out visitors home.
    await expect(page.getByTestId('sign-in')).toBeVisible();
    await expect(page.getByTestId('workspace-list')).toHaveCount(0);
  });

  test('the owner signs in through Keycloak, sees the demo workspace and signs out @responsive', async ({ page }) => {
    await signIn(page, OWNER);
    await page.goto('/workspaces');
    await expect(page.getByTestId('workspace-list')).toContainText(/demo workspace/i);

    await page.getByTestId('user-menu-button').click();
    await page.getByTestId('sign-out').click();

    await expect(page.getByTestId('sign-in')).toBeVisible();
    // Back navigation after signing out must not show protected data again.
    await page.goBack();
    await expect(page.getByTestId('workspace-list')).toHaveCount(0);
  });
});
