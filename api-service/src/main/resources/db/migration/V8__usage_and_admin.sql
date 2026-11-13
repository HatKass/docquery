-- Phase 6: usage ledger, quotas, workspace-creation limit and dead-letter inbox.

-- --- 1. Usage ledger -------------------------------------------------------------------------
-- Token usage must outlive what caused it: deleting a conversation, a document or a whole
-- workspace does not un-spend the tokens. The ledgers therefore have no foreign key to
-- conversations or workspaces (workspace_id is kept as a plain value; reports show
-- "deleted workspace" for ids that no longer exist).

-- One row per assistant answer that called the chat model (query rewrite included).
CREATE TABLE chat_usage (
    message_id         uuid        PRIMARY KEY,         -- the answer (chat_message.id); no FK, see above
    workspace_id       uuid        NOT NULL,
    user_id            uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    model              text        NOT NULL,
    prompt_tokens      integer     NOT NULL CHECK (prompt_tokens >= 0),
    completion_tokens  integer     NOT NULL CHECK (completion_tokens >= 0),
    -- true when the provider reported no usage and tokens were estimated from text length.
    estimated          boolean     NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX chat_usage_workspace_idx ON chat_usage (workspace_id, created_at);
CREATE INDEX chat_usage_created_idx ON chat_usage (created_at);

-- Answers stored before this migration (Phase 5).
INSERT INTO chat_usage (message_id, workspace_id, user_id, model, prompt_tokens, completion_tokens, estimated, created_at)
SELECT m.id, m.workspace_id, c.user_id, m.model, m.prompt_tokens, coalesce(m.completion_tokens, 0),
       coalesce(m.tokens_estimated, true), m.created_at
  FROM chat_message m
  JOIN conversation c ON c.workspace_id = m.workspace_id AND c.id = m.conversation_id
 WHERE m.role = 'ASSISTANT' AND m.model IS NOT NULL AND m.prompt_tokens IS NOT NULL;

-- Embedding usage (written by the ingestion worker since Phase 4) now survives workspace deletion too.
ALTER TABLE embedding_usage DROP CONSTRAINT embedding_usage_workspace_id_fkey;
CREATE INDEX embedding_usage_created_idx ON embedding_usage (created_at);

-- --- 2. Daily quotas -------------------------------------------------------------------------
-- Counters, not counts of existing rows: deleting a conversation or a document must not give a
-- question or an upload back. Incremented atomically together with the action (see QuotaService).
CREATE TABLE daily_usage_counter (
    user_id     uuid    NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    usage_date  date    NOT NULL,                      -- UTC day
    questions   integer NOT NULL DEFAULT 0 CHECK (questions >= 0),
    uploads     integer NOT NULL DEFAULT 0 CHECK (uploads >= 0),
    PRIMARY KEY (user_id, usage_date)
);

-- --- 3. Workspace-creation limit -------------------------------------------------------------
-- NULL: the configured default applies. Set by a platform admin; 0 means "no limit".
ALTER TABLE app_user ADD COLUMN workspace_limit_override integer CHECK (workspace_limit_override >= 0);

-- "How many workspaces did this user create (that still exist)?"
CREATE INDEX workspace_created_by_idx ON workspace (created_by);

-- --- 4. Dead-letter inbox --------------------------------------------------------------------
-- api-service consumes document.uploaded.dlt into this table so admins can inspect and retry
-- failed ingestion events from the browser. (dlt_topic, dlt_partition, dlt_offset) identifies the
-- Kafka record: a redelivered record is recognised and stored once.
CREATE TABLE dead_letter_event (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    dlt_topic           text        NOT NULL,
    dlt_partition       integer     NOT NULL,
    dlt_offset          bigint      NOT NULL,
    original_topic      text,
    original_partition  integer,
    original_offset     bigint,
    message_key         text,
    payload             text,
    -- Parsed from the payload when it is a valid document.uploaded event; NULL otherwise. No FKs:
    -- the record stays inspectable after the document or workspace is deleted.
    event_id            uuid,
    document_id         uuid,
    workspace_id        uuid,
    exception_class     text,
    exception_message   text,
    failed_at           timestamptz NOT NULL,
    received_at         timestamptz NOT NULL DEFAULT now(),
    status              text        NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'RETRIED', 'DISMISSED')),
    resolved_by         uuid        REFERENCES app_user (id) ON DELETE SET NULL,
    resolved_at         timestamptz,
    CONSTRAINT dead_letter_event_record_key UNIQUE (dlt_topic, dlt_partition, dlt_offset),
    CONSTRAINT dead_letter_event_resolved_check CHECK ((status = 'PENDING') = (resolved_at IS NULL))
);

CREATE INDEX dead_letter_event_status_idx ON dead_letter_event (status, failed_at DESC);
