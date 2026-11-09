# 0004. Workspace roles in the database; tenant isolation enforced in every query

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

A user can belong to several workspaces, with a different role in each (OWNER, MEMBER, VIEWER).
Workspace owners manage membership from the app. The most serious bug this product could have is one
tenant seeing another tenant's documents, chunks, chats or citations, including through vector search.

Options considered for roles:

1. **Keycloak groups/roles in the token.** Tokens would grow with every workspace, changes would only
   apply after a token refresh, and owners would need admin rights in Keycloak.
2. **Roles in the application database.** Changes apply immediately and stay with the data they protect.

Options considered for isolation:

1. **Application-level checks + workspace filter in every query.**
2. **PostgreSQL row-level security (RLS)** keyed on a per-transaction setting.

## Decision

- Roles live in `workspace_member (workspace_id, user_id, role)`. Each role includes the ones below it.
- `WorkspaceAccess.require(workspaceId, user, minimumRole)` is the single authorization entry point. Every
  workspace-scoped use case calls it first.
- **Non-members get 404, never 403.** A workspace you do not belong to looks exactly like one that does not
  exist, so ids cannot be probed. Members with too low a role get 403.
- **Every query is additionally scoped by workspace**, and workspace reads join through the caller's
  membership. A missing access check therefore still cannot return another tenant's rows. Later phases
  apply the same rule to documents, chunks (including the pgvector similarity query), chats and citations.
- A workspace always keeps at least one owner. Membership changes lock the workspace row so concurrent
  requests cannot remove the last two owners at the same time.
- **Tests guard the rule:** `TenantIsolationTests` calls every workspace-scoped endpoint as an outsider
  (expects 404 and no side effects), and **fails if a new `/api/workspaces/{workspaceId}/…` endpoint is
  added without being added to that test.**

## Consequences

- Authorization is plain, readable Java and SQL, tested with real Postgres.
- Isolation depends on discipline in the code. The guard test and the "always filter by workspace" rule
  make that discipline enforceable. RLS stays an option for defence in depth later. We did not add it now
  because it complicates connection pooling (setting tenant context per transaction) and the ingestion
  worker's batch jobs.
- People must sign in once before an owner can add them (they need a local user row). E-mail invitations
  are out of scope for now.
