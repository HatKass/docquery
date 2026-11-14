import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, type APIResponse, type Locator, type Page } from '@playwright/test';

/** Seeded by infra/keycloak/realm/docquery-realm.json and the api-service demo migration (local only). */
export const DEMO_WORKSPACE_ID = '00000000-0000-4000-8000-000000000001';

export interface DemoUser {
  username: string;
  displayName: string;
}

export const OWNER: DemoUser = { username: 'owner', displayName: 'Olivia Owner' };
export const VIEWER: DemoUser = { username: 'viewer', displayName: 'Vera Viewer' };

/** The local demo password from the realm export; override for another environment. */
const DEMO_PASSWORD = process.env['E2E_DEMO_PASSWORD'] ?? 'demo123';

/** Signs in through the real Keycloak login page (authorization code + PKCE). */
export async function signIn(page: Page, user: DemoUser): Promise<void> {
  await page.goto('/');
  await page.getByTestId('sign-in').click();
  // Keycloak's own login form (another origin).
  await page.locator('#username').fill(user.username);
  await page.locator('#password').fill(DEMO_PASSWORD);
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('user-menu-button')).toBeVisible();
}

/** The signed-in user's access token, from oidc-client-ts's session storage. */
async function accessToken(page: Page): Promise<string> {
  return page.evaluate(() => {
    const key = Object.keys(sessionStorage).find((k) => k.startsWith('oidc.user:'));
    if (!key) {
      throw new Error('Not signed in');
    }
    return JSON.parse(sessionStorage.getItem(key)!).access_token as string;
  });
}

/** Calls the API as the signed-in user (setup and cleanup that the test is not about). */
export async function callApi(page: Page, method: string, path: string, data?: unknown): Promise<APIResponse> {
  const response = await page.request.fetch(path, {
    method,
    headers: { Authorization: `Bearer ${await accessToken(page)}` },
    data,
  });
  expect(response.ok(), `${method} ${path}: ${response.status()} ${await response.text()}`).toBeTruthy();
  return response;
}

/** A sample document from /sample-data under a unique name, so runs never collide. */
export function sampleFile(fileName: string, mimeType: string): { name: string; mimeType: string; buffer: Buffer } {
  const buffer = readFileSync(resolve(__dirname, '..', '..', '..', 'sample-data', fileName));
  const stamp = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 6)}`;
  return { name: `e2e-${stamp}-${fileName}`, mimeType, buffer };
}

export function documentRow(page: Page, fileName: string): Locator {
  return page.locator('tr[data-document-id]').filter({ hasText: fileName });
}

/** Uploads through the library's file input and waits until the worker has indexed the file. */
export async function uploadAndWaitUntilReady(
  page: Page,
  file: { name: string; mimeType: string; buffer: Buffer },
): Promise<Locator> {
  await page.goto(`/workspaces/${DEMO_WORKSPACE_ID}`);
  await page.getByTestId('file-input').setInputFiles(file);
  const row = documentRow(page, file.name);
  await expect(row).toBeVisible();
  // Status arrives live over SSE: Uploaded → Extracting → Chunking → Embedding → Ready.
  await expect(row.getByTestId('status')).toHaveText(/Ready/, { timeout: 150_000 });
  return row;
}

/** Deletes a document through the shared ConfirmDialog (never window.confirm). */
export async function deleteDocument(page: Page, fileName: string): Promise<void> {
  await page.goto(`/workspaces/${DEMO_WORKSPACE_ID}`);
  const row = documentRow(page, fileName);
  await row.getByTestId('delete-document').click();
  const dialog = page.getByTestId('confirm-dialog');
  await expect(dialog).toBeVisible();
  await dialog.getByTestId('confirm-accept').click();
  await expect(row).toHaveCount(0);
}

/** No horizontal page scroll (PROJECT_SPEC §10: layouts work at phone width). */
export async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow, 'page is wider than the viewport').toBeLessThanOrEqual(0);
}
