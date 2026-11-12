# 0007. Kafka for ingestion events: transactional outbox, idempotent worker, retries and a dead-letter topic

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

An upload must reliably lead to indexing, and the worker's progress must reliably reach the UI,
even when Kafka, the database or the embedding provider is briefly down, events are delivered
twice, or a file can never be processed. Losing an event leaves a document "Queued" forever;
processing one twice must not duplicate chunks or cost.

## Decision

**Topics** (declared by the services, broker auto-creation is off), keyed by document id so each
document's events stay in order on one partition:

| Topic | Producer → consumer | Content |
|-------|---------------------|---------|
| `document.uploaded` | api-service → worker (group `ingestion-worker`) | event id, document, workspace, storage key, content type, reason (`UPLOAD`/`REINDEX`) |
| `document.status` | worker → api-service (group `api-service`) | status, page/chunk counts, failure reason |
| `document.uploaded.dlt` | worker → inspection/replay (Phase 6) | original record + exception headers |

**Transactional outbox in api-service.** The upload (or re-index) and its `document.uploaded` event
are written in **one database transaction** (`outbox_event`). A relay polls the outbox
(`FOR UPDATE SKIP LOCKED`, oldest first), sends each event, waits for Kafka's ack and deletes the
row. A Kafka outage only delays processing; it never loses an event or fails an upload. Delivery is
at least once.

**Idempotent worker.** Each `document.uploaded` event carries an `eventId`. The worker records every
finished event in `ingestion_processed_event` in the same transaction that replaces the document's
chunks. A redelivered event is not processed again; its recorded outcome is re-announced (which also
covers a crash between commit and announcing READY). Writing chunks *replaces* the previous set, so
re-indexing and concurrent duplicates still end in exactly one consistent set. Events for deleted
documents are skipped; the row lock taken while writing (`FOR SHARE`) prevents chunks outliving a
concurrent delete.

**Retries and dead-letter topic.** Spring Kafka's `DefaultErrorHandler`:

- *Permanent* errors (damaged or password-protected file, no text, too long, malformed message) are
  not retried.
- *Transient* errors (embedding provider, storage, database, Kafka) are retried with exponential
  backoff (default 4 attempts: 2 s, 6 s, 18 s).
- Then the recoverer records the failure, publishes `FAILED` with a reason a user can act on
  (internals stay in the logs), and publishes the original record to `document.uploaded.dlt`.

**Status updates in api-service** go through `DocumentStatusService`, which only accepts forward
transitions, so repeated or stale events are ignored. A failing database is retried with capped
exponential backoff and never skipped; malformed status records are logged and skipped.

## Consequences

- Correct under redelivery, restarts and outages, verified by integration tests against real Kafka
  and Postgres (duplicate events, transient and persistent provider failures, damaged files,
  deleted documents, malformed messages).
- A failed document can be retried by a member ("Retry"/"Re-index"), which queues a new event id.
  Replaying a dead-letter record as is would only re-announce its failure; Phase 6's "retry"
  action will therefore go through re-index.
- This supersedes one note in ADR 0005: status events are consumed by **one** consumer group shared
  by all api-service replicas (each event applied once). Live SSE updates therefore reach only
  browsers connected to the replica that consumed the event; fanning out across replicas
  (per-replica broadcast group or Postgres `LISTEN/NOTIFY`) is left to the Kubernetes phase.
- `ingestion_processed_event` grows by one row per event; a retention job can prune old rows.
- In-memory retries hold the partition while backing off; with `max.poll.records=1` and a
  15-minute poll interval this is safe, but very long outages are better served by retry topics.
