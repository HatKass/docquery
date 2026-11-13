-- Phase 5: hybrid retrieval (full-text search next to pgvector) and RAG chat history.

-- Full-text search over chunks. A generated column keeps the index in step with the text without
-- any change to the ingestion worker (it only inserts content). 'english' stems words ("policies"
-- matches "policy"); the sample documents and the demo are English. See ADR 0011.
ALTER TABLE document_chunk
    ADD COLUMN content_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

CREATE INDEX document_chunk_content_tsv_idx ON document_chunk USING gin (content_tsv);

-- A conversation belongs to one user in one workspace: history is private, not shared with the
-- other members (they see the same documents, not each other's questions).
CREATE TABLE conversation (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  uuid        NOT NULL REFERENCES workspace (id) ON DELETE CASCADE,
    -- Leaving a workspace keeps the history (rejoining shows it again); deleting the user removes it.
    user_id       uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    title         text        NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    created_at    timestamptz NOT NULL DEFAULT now(),
    updated_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT conversation_workspace_id_key UNIQUE (workspace_id, id)
);

-- "My conversations in this workspace, most recent first."
CREATE INDEX conversation_owner_idx ON conversation (workspace_id, user_id, updated_at DESC, id);

CREATE TABLE chat_message (
    id                 uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id       uuid        NOT NULL,
    conversation_id    uuid        NOT NULL,
    role               text        NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content            text        NOT NULL,
    -- COMPLETE, or FAILED when the model errored or the answer was interrupted (kept for the record,
    -- never sent back to the model as history).
    status             text        NOT NULL CHECK (status IN ('COMPLETE', 'FAILED')),
    -- Assistant answers only: which model answered and what it cost (aggregated in Phase 6).
    model              text,
    prompt_tokens      integer     CHECK (prompt_tokens >= 0),
    completion_tokens  integer     CHECK (completion_tokens >= 0),
    -- true when the provider reported no usage and tokens were estimated from text length.
    tokens_estimated   boolean,
    created_at         timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT chat_message_conversation_fk FOREIGN KEY (workspace_id, conversation_id)
        REFERENCES conversation (workspace_id, id) ON DELETE CASCADE
);

CREATE INDEX chat_message_conversation_idx ON chat_message (conversation_id, created_at, id);

-- The sources an answer cites. Name, page and snippet are a snapshot: the citation stays readable
-- after the document is re-indexed or deleted (document_id then becomes NULL).
CREATE TABLE chat_citation (
    message_id    uuid     NOT NULL REFERENCES chat_message (id) ON DELETE CASCADE,
    -- The [n] marker used in the answer text.
    source_number integer  NOT NULL CHECK (source_number >= 1),
    workspace_id  uuid     NOT NULL,
    document_id   uuid,
    document_name text     NOT NULL,
    content_type  text     NOT NULL,
    page_number   integer  CHECK (page_number >= 1),
    snippet       text     NOT NULL,
    PRIMARY KEY (message_id, source_number),
    -- Same tenant guard as chunks: a citation can only point at a document of its own workspace.
    CONSTRAINT chat_citation_document_fk FOREIGN KEY (workspace_id, document_id)
        REFERENCES document (workspace_id, id) ON DELETE SET NULL (document_id)
);

CREATE INDEX chat_citation_document_idx ON chat_citation (document_id);
