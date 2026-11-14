import { defineConfig, devices } from '@playwright/test';

/**
 * End-to-end tests against the running stack: Angular (ng serve), api-service, ingestion-worker,
 * Keycloak, Postgres, Kafka, object storage and a model provider (Ollama by default).
 * See e2e/README.md for how to start it. Nothing is mocked: the tests sign in through Keycloak,
 * upload real files, wait for the worker and ask the real chat model.
 */
const baseURL = process.env['E2E_BASE_URL'] ?? 'http://localhost:4200';

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/support/global-setup.ts',
  // The demo workspace is shared: tests that upload and delete documents run one at a time.
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  // Ingestion and answers depend on a local model: generous, but bounded.
  timeout: 180_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'desktop',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } },
    },
    {
      // UI standards (PROJECT_SPEC §10): every page works at phone width.
      name: 'mobile',
      use: { ...devices['Pixel 7'] },
      grep: /@responsive/,
    },
  ],
});
