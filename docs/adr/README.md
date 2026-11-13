# Architecture Decision Records

Each significant decision is recorded as a short ADR ([Michael Nygard format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)):
context → decision → consequences. ADRs are immutable once accepted; a change of mind is a new ADR that supersedes the old one.

| #    | Title                                                                  | Status   |
|------|------------------------------------------------------------------------|----------|
| 0001 | [Platform versions: Spring Boot 4.1 + Spring AI 2.0](0001-platform-versions.md) | Accepted |
| 0002 | [S3-compatible object storage: SeaweedFS instead of MinIO](0002-s3-compatible-object-storage.md) | Accepted |
| 0003 | [Keycloak for identity, OIDC code flow + PKCE, stateless JWT validation](0003-keycloak-for-identity.md) | Accepted |
| 0004 | [Workspace roles in the database; tenant isolation enforced in every query](0004-workspace-authorization-and-tenant-isolation.md) | Accepted |
| 0005 | [Document uploads: content-based validation, S3 storage, live status over SSE](0005-document-upload-and-live-status.md) | Accepted (consumer-group note superseded by 0007) |
| 0006 | [Two backend services: api-service and ingestion-worker](0006-two-backend-services.md) | Accepted |
| 0007 | [Kafka for ingestion events: transactional outbox, idempotent worker, retries and a dead-letter topic](0007-kafka-ingestion-events.md) | Accepted |
| 0008 | [Chunks and vectors in PostgreSQL + pgvector; page-aware chunking](0008-pgvector-chunk-storage.md) | Accepted |
| 0009 | [Model providers behind Spring AI: Ollama locally, a cloud provider in production](0009-model-provider-abstraction.md) | Accepted |
| 0010 | [Backfill pre-pipeline documents with a migration; let members retry documents stuck in the queue](0010-backfill-and-stuck-document-safety-net.md) | Accepted |
| 0011 | [Hybrid retrieval: pgvector + PostgreSQL full-text search, merged with reciprocal rank fusion](0011-hybrid-retrieval-with-reciprocal-rank-fusion.md) | Accepted |
| 0012 | [Grounded, streamed answers with server-side citations and prompt-injection hygiene](0012-grounded-answers-and-prompt-injection-hygiene.md) | Accepted |
| 0013 | [Usage ledger, quotas and a platform-admin role](0013-usage-ledger-quotas-and-platform-admin.md) | Accepted |

Use [`template.md`](template.md) for new records.
