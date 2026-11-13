-- Phase 7: observability. The trace context (W3C traceparent/tracestate) of the request that queued an
-- outbox event, so the relayed Kafka message continues that trace instead of starting a new one.
-- NULL when tracing was off or the event was queued outside a traced request.
ALTER TABLE outbox_event ADD COLUMN trace_headers jsonb;
