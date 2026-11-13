# 0013. Usage ledger, quotas and a platform-admin role

- **Status:** Accepted
- **Date:** 2026-11-13

## Context

DocQuery will run as a public demo that calls paid models. We need to know what each workspace and
user costs, cap what any one user can spend per day, limit how many workspaces a user can create
(carry-over note from Phase 5), and give an operator a page to see pipeline failures and retry
dead-lettered ingestion events. Phase 5 already stores tokens on each answer and Phase 4 records
embedding tokens, but both live next to data users can delete (conversations, workspaces).

## Decision

**Usage ledger.** Chat usage is written to `chat_usage` in the same transaction as the answer;
embedding usage stays in the worker's `embedding_usage`. Neither has a foreign key to conversations or
workspaces: spent tokens stay on the books when a conversation, a document or a workspace is deleted
(reports show "deleted workspace"). Answers that needed no model call (nothing relevant found) cost
nothing; failed answers that did call the model are charged.

**Cost is an estimate computed at report time** from token counts and a configured price table
(`USAGE_PRICES`, USD per million input/output tokens). Price changes therefore apply retroactively,
which is acceptable for estimates and avoids storing a second, stale copy of prices. Models without a
configured price are reported as *unpriced*, never silently as free. Reports group by UTC day,
model, user and (admin only) workspace.

**Quotas are counters, not counts of rows.** `daily_usage_counter` holds questions and uploads per
user per UTC day. Counting existing rows would let users delete conversations or documents to get
their quota back. Each check is one conditional `INSERT … ON CONFLICT DO UPDATE … WHERE count < limit`
inside the transaction that stores the question or the upload, so concurrent requests cannot
overshoot and a rejected action uses nothing. Exceeded daily quotas answer **429** with a
`Retry-After` header and a friendly ProblemDetail (limit, reset time); count limits answer **409**.

**Workspace-creation limit.** A user may create `QUOTA_MAX_WORKSPACES_PER_USER` workspaces (default 3)
that still exist, counted by `workspace.created_by` under a row lock on the user. Deleting a workspace
frees its slot; handing it to another owner does not, so the limit cannot be bypassed by creating and
abandoning workspaces. A platform admin can override the limit per user (`app_user.workspace_limit_override`;
`null` = default, `0` = no limit).

**Platform admin is a Keycloak realm role** (`docquery-admin`, configurable). It is the only global role
and the only authorization data in Keycloak: it is an operator privilege, not per-tenant business data
(workspace roles stay in the database, ADR 0004). The token converter maps `realm_access.roles` to
`ROLE_ADMIN`, and `/api/admin/**` requires it in one place in `SecurityConfiguration`. Admins are **not**
members of every workspace: they see usage, failures and metadata, never documents or conversations.

**Dead letters.** api-service consumes `document.uploaded.dlt` into `dead_letter_event` (idempotent on
the record's topic/partition/offset), adding status, resolver and joins to document and workspace.
"Retry" does not replay the Kafka record (the worker already recorded that event id as FAILED); it
re-queues the document with a new event, exactly like a member's retry. Events of deleted documents
or with unreadable payloads can only be dismissed.

## Consequences

- Costs are visible per workspace (to its owners) and per platform (to admins) without a billing system.
- Cost figures are estimates; real invoices come from the provider. Prices must be kept up to date.
- Quota limits are global defaults; per-user overrides exist only for the workspace limit. Per-user
  question quotas or a platform-wide daily cap can be added the same way if the demo needs them (Phase 9).
- Platform admins must be managed in Keycloak (role assignment), not in the app.
- With several api-service replicas the DLT consumer group shares the work; the table is the single
  source of truth for the admin page.
