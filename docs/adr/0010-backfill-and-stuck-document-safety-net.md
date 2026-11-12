# 0010. Backfill pre-pipeline documents with a migration; let members retry documents stuck in the queue

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Documents uploaded before the ingestion pipeline existed (Phase 3) are in status `UPLOADED`
but never got a `document.uploaded` event, so they would stay "Queued" forever, and the UI offered
no action for them. More generally, a queued document whose request is lost (an operator purged a
topic, a bug, a restored backup) has no way out, because re-indexing (ADR 0007) only accepted
finished documents.

## Decision

**1. One-off backfill as a Flyway migration (`V5__backfill_ingestion_requests.sql`).** It inserts
an outbox event (reason `BACKFILL`) for every document that is `UPLOADED`, has no pending outbox
event and was never processed by the worker; the relay publishes them like any other event.

A migration rather than a startup task, because it:

- runs **exactly once** per database, recorded in `flyway_schema_history`;
- is **atomic** (all events or none) and ordered after the schema it needs (V4);
- needs no locking between api-service replicas (Flyway already serializes migrations);
- leaves no permanent code path that runs on every boot.

A startup task would run on every start of every replica and need its own "already done"
bookkeeping. A document uploaded after V4 whose event was relayed but not yet consumed when V5
runs could be queued twice; the idempotent pipeline makes that harmless (chunks are replaced,
repeated statuses ignored).

**2. Safety net for stuck documents.** `POST /workspaces/{id}/documents/{docId}/reindex` (MEMBER)
also accepts a document that has been `UPLOADED` for longer than `DOCUMENT_REQUEUE_AFTER`
(default 10 minutes). It queues a new event (reason `REQUEUE`) and restarts the queue timer
(`status_changed_at`) in one conditional update, so two concurrent clicks cannot both succeed and an
immediate repeat is refused (409). Documents in `EXTRACTING`/`CHUNKING`/`EMBEDDING` are not
eligible: the worker either finishes them or ends in `FAILED` after its retries.

The threshold is published in `GET /documents/upload-policy` (`requeueAfterSeconds`); the library
shows **Retry** for such documents (re-evaluated every 30 s, no reload needed). The API enforces the
threshold itself, so the button is only a convenience.

## Consequences

- Upgrading an existing installation indexes its old documents automatically on first start.
- A member can always get a document out of the queue; the worst case of retrying while the
  original request is merely slow is one duplicate, idempotent run (extra embedding cost only).
- Workspace access, roles and tenant isolation are unchanged: the endpoint checks membership and
  role first and scopes every query by workspace (covered by `TenantIsolationTests` and dedicated
  tests for stuck documents).
