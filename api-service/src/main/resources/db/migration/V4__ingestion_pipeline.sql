-- Phase 4: ingestion pipeline (chunks + vectors, transactional outbox, idempotency, usage).
--
-- api-service owns the whole schema (Flyway runs only there). The ingestion worker reads
-- document rows and writes document_chunk, ingestion_processed_event and embedding_usage.

-- Lets chunks reference (workspace_id, id) so a chunk can never claim a workspace other than
-- its document's: tenant isolation is enforced by the schema, not only by queries.
ALTER TABLE document ADD CONSTRAINT document_workspace_id_key UNIQUE (workspace_id, id);

CREATE TABLE document_chunk (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id          uuid        NOT NULL,
    document_id           uuid        NOT NULL,
    -- Position in the document (0-based, contiguous), used to order and cite chunks.
    chunk_index           integer     NOT NULL CHECK (chunk_index >= 0),
    -- 1-based page for paginated formats (PDF); NULL for DOCX/TXT/Markdown, which have no pages.
    page_number           integer     CHECK (page_number >= 1),
    -- Character range of the chunk within its page's (or the whole document's) extracted text.
    start_offset          integer     NOT NULL CHECK (start_offset >= 0),
    end_offset            integer     NOT NULL,
    content               text        NOT NULL CHECK (length(content) > 0),
    -- Dimension-less column: models with different dimensions can coexist during a re-index.
    embedding             vector      NOT NULL,
    embedding_model       text        NOT NULL,
    embedding_dimensions  integer     NOT NULL CHECK (embedding_dimensions > 0),
    created_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT document_chunk_offsets_check CHECK (end_offset > start_offset),
    CONSTRAINT document_chunk_dimensions_check CHECK (vector_dims(embedding) = embedding_dimensions),
    CONSTRAINT document_chunk_document_fk FOREIGN KEY (workspace_id, document_id)
        REFERENCES document (workspace_id, id) ON DELETE CASCADE,
    CONSTRAINT document_chunk_position_key UNIQUE (document_id, chunk_index)
);

-- Every retrieval query filters by workspace first.
CREATE INDEX document_chunk_workspace_idx ON document_chunk (workspace_id);

-- Approximate nearest-neighbour index for 768-dimension models (nomic-embed-text, or OpenAI
-- text-embedding-3-* with dimensions=768). HNSW needs a fixed dimension, hence the cast and the
-- partial predicate; a model with another dimension gets its own index in a later migration.
CREATE INDEX document_chunk_embedding_768_idx ON document_chunk
    USING hnsw ((embedding::vector(768)) vector_cosine_ops)
    WHERE embedding_dimensions = 768;

-- Transactional outbox: events are written in the same transaction as the change that caused
-- them and published to Kafka afterwards, so an upload can never be committed without its
-- "document.uploaded" event (or the other way round). Rows are deleted once Kafka acknowledged them.
CREATE TABLE outbox_event (
    id            uuid        PRIMARY KEY,
    topic         text        NOT NULL,
    message_key   text        NOT NULL,
    payload       jsonb       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    attempts      integer     NOT NULL DEFAULT 0,
    last_error    text
);

CREATE INDEX outbox_event_created_idx ON outbox_event (created_at, id);

-- Idempotent consumer: one row per document.uploaded event the worker finished (successfully or
-- not). A redelivered event is recognised and only its outcome is re-announced.
-- No foreign key: the record must survive the document's deletion.
CREATE TABLE ingestion_processed_event (
    event_id        uuid        PRIMARY KEY,
    document_id     uuid        NOT NULL,
    outcome         text        NOT NULL CHECK (outcome IN ('READY', 'FAILED')),
    failure_reason  text,
    page_count      integer,
    chunk_count     integer,
    processed_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ingestion_processed_event_reason_check CHECK ((outcome = 'FAILED') = (failure_reason IS NOT NULL))
);

CREATE INDEX ingestion_processed_event_document_idx ON ingestion_processed_event (document_id);

-- Embedding token usage, per indexing run. Feeds cost tracking and quotas (Phase 6).
-- document_id has no foreign key: the cost was incurred even if the document is deleted later.
CREATE TABLE embedding_usage (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    uuid        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    user_id         uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    document_id     uuid        NOT NULL,
    model           text        NOT NULL,
    input_tokens    bigint      NOT NULL CHECK (input_tokens >= 0),
    -- true when the provider reported no usage and tokens were estimated from text length.
    estimated       boolean     NOT NULL,
    request_count   integer     NOT NULL CHECK (request_count >= 0),
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX embedding_usage_workspace_idx ON embedding_usage (workspace_id, created_at);
