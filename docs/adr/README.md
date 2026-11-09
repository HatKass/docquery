# Architecture Decision Records

Each significant decision is recorded as a short ADR ([Michael Nygard format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)):
context → decision → consequences. ADRs are immutable once accepted; a change of mind is a new ADR that supersedes the old one.

| #    | Title                                                                  | Status   |
|------|------------------------------------------------------------------------|----------|
| 0001 | [Platform versions: Spring Boot 4.1 + Spring AI 2.0](0001-platform-versions.md) | Accepted |
| 0002 | [S3-compatible object storage: SeaweedFS instead of MinIO](0002-s3-compatible-object-storage.md) | Accepted |

Planned (later phases): two backend services, Kafka for ingestion events, hybrid search with RRF,
pgvector vs a dedicated vector DB, Keycloak for identity, LLM provider abstraction.

Use [`template.md`](template.md) for new records.
