# 0014. Observability: OpenTelemetry traces across the outbox and Kafka, Prometheus metrics, logs in Loki, Grafana

- **Status:** Accepted
- **Date:** 2026-11-13

## Context

A document passes through two services and three hops before it is READY: the upload request writes
an outbox row, a scheduler thread relays it to Kafka (`document.uploaded`), the worker extracts, chunks,
embeds and indexes it, and four `document.status` events go back to api-service. When a document is
slow or fails, we need to see that whole path as one picture, not stitch together two services' logs
by timestamp. We also need the usual service metrics (latency, errors, JVM, database pool, Kafka lag),
RAG-specific ones (time to first token, retrieval time, model latency and tokens), and logs a machine
can parse.

The transactional outbox (ADR 0007) breaks automatic trace propagation: the Kafka message is sent
seconds later, by another thread, outside the request.

## Decision

**One standard per signal, all from Spring Boot 4.1's own support.**

- **Traces:** Micrometer Tracing on the OpenTelemetry SDK (`spring-boot-starter-opentelemetry`),
  exported over OTLP/HTTP to **Tempo**. W3C `traceparent` propagation. Spring Kafka's template and
  listener observations are enabled in both services, so every produced record carries the trace and
  every consumer span continues it. Sampling is parent-based (100 % locally, `TRACING_SAMPLING_PROBABILITY`
  in production), so a trace is never cut in half between the services.
- **Across the outbox:** the request's trace context is stored with the event (`outbox_event.trace_headers`,
  migration V9). The relay starts an `outbox relay` span as a child of that context and sends inside it,
  so the producer span, the record's `traceparent`, the worker and the status events back all join the
  upload's trace. Result: `POST /documents → outbox relay → document.uploaded send → document.uploaded
  process → ingestion extract/chunk/embed/index (+ Spring AI embedding span) → 4× document.status send →
  document.status process`.
- **Metrics:** Micrometer with the **Prometheus** registry; Prometheus scrapes `/actuator/prometheus`.
  Pull rather than OTLP push for metrics: it is the most common setup (Kubernetes ServiceMonitors in
  Phase 9), and a down target is visible as such. Custom metrics: `docquery.ingestion.step`
  (per-step timer and span), `docquery.ingestion.duration` (upload → READY/FAILED, what users wait),
  `docquery.ingestion.documents`, `docquery.outbox.pending` / `.oldest.age`, `docquery.chat.answer`
  (by outcome), `docquery.chat.first.token`, `docquery.retrieval`, `docquery.documents.uploaded`,
  `docquery.documents.status.changes`, `docquery.api.problems` (business errors by ProblemDetail code),
  `docquery.embedding.tokens`. Spring AI adds `gen_ai.client.operation` and `gen_ai.client.token.usage`.
- **Logs:** JSON on the console in the Elastic Common Schema (`LOG_FORMAT=json`, the default in
  containers; readable text when run from source), with trace and span ids in both formats. Every
  record is also exported over OTLP to **Loki** (OpenTelemetry Logback appender), where `trace_id` is
  structured metadata: Grafana jumps from a span to its log lines and back.
- **Grafana** with provisioned data sources (linked: exemplars and log lines → Tempo, spans → Loki)
  and two dashboards committed as JSON (`infra/observability/grafana/dashboards`): *DocQuery Overview*
  (API, pipeline, chat, recent traces) and *DocQuery JVM and resources*. Tempo's metrics generator
  writes span metrics and the service graph to Prometheus.
- **Noise and privacy rules.** Not traced: actuator requests (health probes, scrapes), the relay's
  500 ms polling runs, Spring Security's filter-chain spans. Metric labels never contain tenant or user
  ids or content (bounded cardinality, nothing confidential in a shared monitoring system); ids appear
  only as span attributes. Spring AI's prompt and completion content is not recorded (its default), and
  the question text is no longer logged at INFO.

**Local stack:** Prometheus, Tempo and Loki as single binaries on local disk, plus Grafana, in the core
Compose stack (one `docker compose up` gives you everything). No OpenTelemetry Collector: the services
send OTLP straight to Tempo and Loki. In production a Collector in between adds batching, retries,
tail sampling and lets the backend change without touching the services.

## Consequences

- One trace answers "where did this document spend its time, and why did it fail?" across both
  services, including the time an event waited in the outbox (`docquery.outbox.wait_ms` on the relay span).
- `/actuator/prometheus` is unauthenticated so Prometheus can scrape it. It holds only aggregates, but
  it must not be reachable from the internet: the reverse proxy blocks `/actuator/*` except health
  (Phase 9), or the management endpoints move to a separate port.
- Telemetry export is fire-and-forget: if Tempo or Loki is down, the services log an export warning and
  carry on; nothing user-facing depends on it. Tests keep tracing on but export only to an in-memory
  exporter (`src/test/resources/application.properties`), so they can assert on spans and never send
  telemetry anywhere.
- The Compose stack grows by four containers (~600 MB RAM). Local storage is for development only;
  production uses object storage for Tempo and Loki and a retention policy agreed with the data owner
  (logs and traces contain document and workspace ids).
- The OpenTelemetry Logback appender is not managed by Spring Boot; its version (2.28.x) is pinned to
  the OpenTelemetry SDK version Boot manages and must be bumped together with Boot.
