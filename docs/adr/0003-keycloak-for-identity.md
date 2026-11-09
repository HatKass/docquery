# 0003. Keycloak for identity, OIDC code flow + PKCE, stateless JWT validation

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

DocQuery needs login for a multi-tenant web app and a public demo. Writing our own password storage,
reset flows, brute-force protection and MFA would be a big risk with no upside for the product.
We want a standard protocol (OIDC) so the identity provider can be swapped (Auth0, Entra ID, Cognito)
without touching the API, and a provider we can run locally in Docker with seeded demo users.

## Decision

- **Keycloak** (self-hosted, realm export committed in `infra/keycloak/realm/`) is the identity provider.
- The **Angular SPA** is a *public* OIDC client using the **authorization code flow with PKCE (S256)**,
  via the certified `oidc-client-ts` library. No implicit flow, no client secret in the browser.
  Tokens are kept in `sessionStorage` and refreshed automatically before they expire.
- The **api-service** is a stateless **OAuth2 resource server**. On every request it validates the JWT's
  signature (JWKS from Keycloak), expiry, **issuer** and **audience** (`docquery-api`, added by an
  audience mapper). No sessions, no cookies, so CSRF protection is not needed.
- Users are **provisioned just in time**: the first authenticated request creates a local `app_user`
  row keyed by the token's `sub`. Profile fields are refreshed from the token at most every 5 minutes.
- **Keycloak handles authentication only.** Per-workspace roles are application data in Postgres
  (see [ADR 0004](0004-workspace-authorization-and-tenant-isolation.md)), not Keycloak roles.
- Local development only: a `docquery-dev-cli` client with the password grant, so developers can get a
  token with `curl` and integration tests can run against the real realm. **It must not exist in a
  production realm.**

## Consequences

- The API never sees a password. Swapping identity providers means changing three environment variables
  (`OIDC_ISSUER_URI`, `OIDC_JWK_SET_URI`, `OIDC_AUDIENCE`) and the SPA's authority/client id.
- The issuer URL must be the one the *browser* sees. When the API runs in a container, it fetches keys
  over the Docker network (`OIDC_JWK_SET_URI`) but still checks `iss` against the public URL.
- Access tokens are short-lived (5 minutes). Removing someone from a workspace takes effect on their next
  request, because roles are read from the database, not from the token.
- Tokens in `sessionStorage` can be read by script on our origin, so XSS must be prevented (Angular
  escapes by default; a strict CSP is planned for the deployment phase). A backend-for-frontend with
  HTTP-only cookies would avoid this, at the cost of a stateful component. We may revisit that.
- `KeycloakTokenIntegrationTests` starts the real Keycloak image with the committed realm and checks that
  real tokens are accepted and forged or foreign-realm tokens are rejected.
