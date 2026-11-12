# 0008. Chunks and vectors in PostgreSQL + pgvector; page-aware chunking

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Chunks need their text, their position for citations (document, page, offsets), an embedding, and
(in Phase 5) full-text search. Every query must be filtered by workspace. Options: pgvector in the
existing database, a dedicated vector database (Qdrant, Weaviate, Pinecone), or Spring AI's
`PgVectorStore` with its fixed schema.

## Decision

**pgvector, own schema (`document_chunk`)**, written with plain JDBC:

- `workspace_id` + `document_id` with a **composite foreign key** to `document (workspace_id, id)`:
  a chunk cannot claim another tenant's workspace, and deleting a document or workspace removes its
  chunks by cascade, in the same transaction.
- `page_number` (PDF; `NULL` for DOCX/TXT/MD, which have no fixed pages), `chunk_index`,
  `start_offset`/`end_offset` in the page's normalized text.
- `embedding vector` **without a fixed dimension**, plus `embedding_model` and
  `embedding_dimensions` per chunk (checked to match). Old and new models can coexist during a
  re-index; `ReindexCommand` re-queues documents embedded with another model.
- An **HNSW** cosine index on `embedding::vector(768)` for 768-dimension chunks (nomic-embed-text;
  OpenAI `text-embedding-3-*` with `dimensions=768`). Another dimension gets its own partial index.

A dedicated vector database was rejected for now: it would be a second store to back up, secure and
keep consistent with Postgres (deletes, tenant filters), while pgvector with HNSW handles millions
of vectors, and keeps the vector search, the full-text search and the tenant filter in one SQL
query. `PgVectorStore` was rejected because its fixed schema keeps tenant and citation data in a
JSON metadata column, without foreign keys or typed columns.

**Chunking.** Extracted text is normalized, then split per page into chunks of up to 1,200
characters (about 300 tokens) with 200 characters of overlap. A chunk ends at the best boundary in
its second half: a paragraph, else a sentence or line end, else a space. The overlap starts at a
sentence or word start. Chunks never span pages, so each chunk has exactly one page to cite.
Character-based sizes are deterministic and provider-independent; all sizes are configurable.

## Consequences

- One database for metadata, vectors and (Phase 5) full-text search; tenant isolation is enforced by
  the schema as well as by every query.
- Unpaginated formats cite the document without a page number.
- Scanned PDFs (images only) yield no text and fail with an explanation; OCR is out of scope.
- If vector volume or latency ever outgrows Postgres, the chunk writer and the retrieval query are
  the only places that change.
