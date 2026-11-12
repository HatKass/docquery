-- Phase 4 backfill: queue ingestion for documents uploaded before the pipeline existed.
--
-- Documents uploaded in Phase 3 are in status UPLOADED but never got a "document.uploaded"
-- event, so they would stay "Queued" forever. This one-off migration writes the missing events
-- into the transactional outbox; the outbox relay publishes them on its next poll and the worker
-- indexes the documents as usual.
--
-- Why a Flyway migration and not a startup task: it runs exactly once per database, atomically
-- (all events or none), in order with the schema it depends on (V4), on whichever replica
-- migrates first, and it is recorded in flyway_schema_history. A startup task would run on every
-- boot of every replica and would need its own "already done" bookkeeping and locking.
--
-- "Never got an event" = in UPLOADED, no pending outbox event for the document, and never
-- processed by the worker. A document uploaded after V4 whose event was already relayed but not
-- yet consumed would also match; it would then be processed twice, which the idempotent pipeline
-- handles (chunks are replaced, repeated status events are ignored).

WITH missing AS (
    SELECT gen_random_uuid() AS event_id, d.*
      FROM document d
     WHERE d.status = 'UPLOADED'
       AND NOT EXISTS (SELECT 1 FROM outbox_event o WHERE o.message_key = d.id::text)
       AND NOT EXISTS (SELECT 1 FROM ingestion_processed_event p WHERE p.document_id = d.id)
)
INSERT INTO outbox_event (id, topic, message_key, payload)
SELECT m.event_id,
       'document.uploaded',
       m.id::text,
       -- Same shape as the DocumentUploaded message written by api-service (IngestionMessages).
       jsonb_build_object(
           'eventId', m.event_id,
           'documentId', m.id,
           'workspaceId', m.workspace_id,
           'storageKey', m.storage_key,
           'contentType', m.content_type,
           'filename', m.filename,
           'uploadedBy', m.uploaded_by,
           'reason', 'BACKFILL',
           'occurredAt', to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
  FROM missing m
 ORDER BY m.uploaded_at, m.id;
