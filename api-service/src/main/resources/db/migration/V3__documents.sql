-- Phase 3: documents (metadata only; the bytes live in S3-compatible object storage).
--
-- Every row belongs to exactly one workspace and every query filters by workspace_id,
-- so tenant isolation for documents (and, later, their chunks and citations) hangs off
-- this column. Deleting a workspace cascades to its documents; the stored files are
-- removed by the application after the transaction commits.

CREATE TABLE document (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id       uuid        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    filename           text        NOT NULL CHECK (length(filename) BETWEEN 1 AND 255),
    -- Detected from the file content at upload, never taken from the client's header.
    content_type       text        NOT NULL,
    size_bytes         bigint      NOT NULL CHECK (size_bytes > 0),
    sha256             text        NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    -- Random object key; the original filename is never part of it.
    storage_key        text        NOT NULL UNIQUE,
    status             text        NOT NULL DEFAULT 'UPLOADED'
                                   CHECK (status IN ('UPLOADED', 'EXTRACTING', 'CHUNKING', 'EMBEDDING', 'READY', 'FAILED')),
    -- Human-readable reason shown in the UI; present exactly when status = 'FAILED'.
    failure_reason     text,
    page_count         integer     CHECK (page_count >= 0),
    chunk_count        integer     CHECK (chunk_count >= 0),
    uploaded_by        uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    uploaded_at        timestamptz NOT NULL DEFAULT now(),
    status_changed_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_failure_reason_iff_failed CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    -- The same file is indexed once per workspace: re-uploading it would only double the
    -- embedding cost and duplicate citations.
    CONSTRAINT document_workspace_sha256_key UNIQUE (workspace_id, sha256)
);

-- The document library lists a workspace's documents, newest first.
CREATE INDEX document_workspace_uploaded_idx ON document (workspace_id, uploaded_at DESC, id);
