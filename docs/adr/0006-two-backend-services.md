# 0006. Two backend services: api-service and ingestion-worker

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Indexing a document (parse a PDF, split it, call an embedding model for every chunk, write vectors)
takes seconds to minutes, uses a lot of CPU and memory, and depends on an external model provider
that can be slow or down. Answering API requests needs low latency and must stay up when the model
provider does not. A file parser handling untrusted uploads (PDFBox, POI) is also the most likely
component to crash or hang on a hostile file.

Options: one service with an in-process job queue; two services connected by a broker; a service
per pipeline step (extract, chunk, embed, index).

## Decision

**Two Spring Boot services, no more.**

- **api-service**: everything a user calls: auth, workspaces, documents, search and chat (later),
  usage. Owns the database schema (Flyway).
- **ingestion-worker**: consumes `document.uploaded` from Kafka and runs extract → chunk → embed →
  index; reports progress on `document.status`. It has no public API besides health.

Both use the same PostgreSQL database. The worker reads `document` rows and writes
`document_chunk`, `ingestion_processed_event` and `embedding_usage`; api-service owns all
migrations, and the worker's tests run those same migrations, so the two cannot drift silently.

A service per step was rejected: the steps share one document in memory and run back to back, so
splitting them would add three network hops, three deployables and intermediate storage for no
independent scaling benefit.

## Consequences

- The API stays responsive during heavy indexing, and a parser crash or provider outage only
  affects the worker; uploads keep working and are processed once the worker recovers.
- The worker scales on its own (`INGESTION_CONCURRENCY`, more replicas up to the partition count).
- A shared database couples the services' deployments: a migration must be compatible with the
  running worker (expand/contract), and the worker should start after api-service migrated
  (compose waits for api-service to be healthy).
- Message contracts are duplicated as small records in each service instead of a shared library;
  the topics are the contract and unknown fields are ignored (see ADR 0007).
