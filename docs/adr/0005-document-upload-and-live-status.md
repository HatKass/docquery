# 0005. Document uploads: content-based validation, S3 storage, live status over SSE

- **Status:** Accepted
- **Date:** 2026-11-11

## Context

Users upload PDF, DOCX, TXT and Markdown files into a workspace. The files are untrusted input that
later feeds text extraction and an LLM, so the upload path is a security boundary. Uploads go through
a multi-step pipeline (Phase 4), and the library must show each document's status as it changes,
without the user refreshing.

Questions to settle:

1. How to decide whether a file is acceptable.
2. Where and how to store the bytes.
3. How the browser learns about status changes.

## Decision

**Validation by content, not by name or header.** The client's `Content-Type` is ignored. A small
in-house detector (`DocumentTypeDetector`) checks the bytes: the `%PDF-` signature for PDF, a ZIP whose
central directory contains `[Content_Types].xml` and `word/document.xml` for DOCX (nothing is
decompressed, so a ZIP bomb costs nothing), and strict UTF-8 without binary control characters for
TXT/Markdown. The detected type must match the extension; otherwise the API answers `415` with a
readable reason. Apache Tika is reserved for the ingestion worker, where full parsing happens; for four
formats a 100-line detector is smaller, faster and easier to test than `tika-core` plus its OOXML
detectors.

Other upload rules: a configurable maximum size (`DOCUMENT_MAX_FILE_SIZE`, enforced by the multipart
parser and again by the service, answered with `413`); the filename is sanitized display metadata only
(path parts, control and bidi characters removed); identical content (SHA-256) is rejected per workspace
with `409`, because re-indexing a duplicate would only double embedding cost and duplicate citations.

**Storage.** Files go to the S3-compatible store (see ADR 0002) under
`workspaces/{workspaceId}/documents/{random UUID}`: the user-supplied name never becomes part of a key,
and the per-workspace prefix lets a workspace deletion remove all its files with one listing. The file
is stored *before* the row is inserted (and removed again if the insert fails), and the row is deleted
*before* the file, so a database row never points to a missing file; the worst case of a storage
outage is an orphaned, unreferenced file, which is logged.

**Live status: Server-Sent Events over `fetch`.** `GET /api/workspaces/{id}/documents/events` streams
`document.created`, `document.updated` and `document.deleted` events after their transaction commits,
plus a `ready` event on connect. SSE fits a one-way server→browser feed, works through ordinary HTTP
proxies, and needs no extra protocol. The browser reads the stream with `fetch` rather than
`EventSource`, because `EventSource` cannot send an `Authorization` header and tokens in URLs leak into
logs. After every (re)connect the client re-fetches the list, so events missed while offline do not
matter.

Status changes go through `DocumentStatusService`, which only accepts forward steps
(`UPLOADED → EXTRACTING → CHUNKING → EMBEDDING → READY`, any step → `FAILED` with a reason,
`READY`/`FAILED` → `UPLOADED` to re-index). Replayed or out-of-order updates are ignored, which makes the
Phase 4 Kafka consumer idempotent by construction.

## Consequences

- Only four formats are accepted; adding one (e.g. PPTX) needs a detector rule and a test.
- Removing a member or deleting a workspace closes the affected streams immediately; streams also end
  after `DOCUMENT_EVENT_STREAM_TIMEOUT` (15 min) so a revoked token cannot keep one open.
- Subscribers live in memory: with several api-service replicas, a replica only pushes changes it sees.
  Phase 4 delivers pipeline status from Kafka to every replica (one consumer group per replica);
  uploads/deletes made on another replica then show up on the next reconnect re-sync.
- Orphaned files after a storage outage are possible (logged); a periodic reconciliation job can be
  added if that ever matters in practice.
