# 0002. S3-compatible object storage: SeaweedFS instead of MinIO

- **Status:** Accepted
- **Date:** 2026-11-09

## Context

The original plan named MinIO for local S3-compatible storage. By late 2026 that wasn't viable:

- MinIO stopped publishing community-edition container images in October 2025. The `minio/minio`
  Docker Hub repository now returns 404, and the quay.io images need authentication.
- The community repository went into maintenance mode in December 2025 and was archived in early 2026.

A portfolio project shouldn't depend on an archived component or on images we'd have to build
from source.

Uploaded files are reached only through the **AWS S3 SDK**. Any S3-compatible store is therefore
a configuration change, not a code change.

## Decision

Use **SeaweedFS** (`chrislusf/seaweedfs`, pinned version, Apache-2.0) for local development and
self-hosted deployments:

- Actively maintained, with a long production track record.
- A single container runs master + volume + filer + S3 gateway (`weed server -s3`).
- Credentials come from environment variables and are written to a runtime-only config file at
  container start, so no secrets are committed.

A one-shot `object-storage-init` container uses the official AWS CLI to create the bucket. This
proves the store works with standard S3 tooling.

Alternatives considered:

| Option | Why not (for now) |
|--------|-------------------|
| Community MinIO forks (e.g. `pgsty/minio`) | Small maintainer base; inherits an archived codebase |
| Chainguard MinIO image | Free tier is `latest`-only, which conflicts with our version pinning |
| Garage | Excellent, but needs an extra cluster-layout bootstrap step that adds friction to "one command" startup |
| RustFS | Promising but young |
| LocalStack S3 | Good for tests; heavier and less representative as a long-running store |

## Consequences

- The app's storage config is generic: `S3_ENDPOINT`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_BUCKET`,
  `S3_REGION`. Production can point at AWS S3, Cloudflare R2, Backblaze B2 or MinIO Enterprise
  without code changes.
- Path-style addressing must be enabled in the S3 client (Phase 3) for local SeaweedFS.
- SeaweedFS's S3 API covers what we need (put/get/delete object, bucket ops). Exotic features such as
  object lock aren't used.
