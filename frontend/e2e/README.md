# End-to-end tests (Playwright)

The e2e tests drive a real browser against the **whole running stack**: nothing is mocked. They sign
in through Keycloak, upload a sample document, wait for the worker to index it (live status over
SSE), ask the chat model, open the citation, delete with the confirm dialog, check role-based UI, and
scan pages for accessibility problems (axe, WCAG 2.1 A/AA) and horizontal scrolling at desktop and
phone width.

| File | Covers |
|---|---|
| `auth.spec.ts` | sign-in (authorization code + PKCE), guard for signed-out visitors, sign-out and back navigation |
| `documents-and-chat.spec.ts` | upload → READY, streamed grounded answer with citation, citation viewer, delete; declining an unanswerable question |
| `roles-and-layout.spec.ts` | viewer is read-only; no serious axe violations and no horizontal scroll on every page; phone navigation |

Projects: `desktop` (1280×800, every test) and `mobile` (Pixel 7, tests tagged `@responsive`).

## Running

1. Start the stack (from the repo root): `docker compose up -d`, a native Ollama with
   `nomic-embed-text` and the chat model, then api-service and ingestion-worker
   (`./mvnw spring-boot:run` in each) and the frontend (`npm start`).
2. Once, install the browser: `npx playwright install chromium`.
3. Run:

```bash
cd frontend
npm run e2e
npm run e2e:report
```

A global setup checks first that the app, API, worker and Keycloak answer, and tells you what to start
if not. Environment overrides: `E2E_BASE_URL`, `E2E_KEYCLOAK_URL`, `E2E_WORKER_HEALTH_URL`,
`E2E_DEMO_PASSWORD`.

## What they leave behind

Uploaded files are deleted again, and the roles test deletes the workspace it creates. The chat tests
leave their conversations in the demo workspace and use about 2 of the owner's 50 daily questions per
run. The tests do not rely on the demo data being unchanged: they create what they need.

## Why not in CI (yet)

The journey needs a chat model that follows the grounding rules; a model small enough for a CI runner
is not reliable enough, and a cloud model would need a secret and cost money per run. CI type-checks
the suite (`npm run e2e:typecheck`); running it in CI with a deterministic stub chat model needs a
stub provider in api-service and is a follow-up.
