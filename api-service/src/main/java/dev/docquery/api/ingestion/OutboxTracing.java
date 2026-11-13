package dev.docquery.api.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

import org.springframework.stereotype.Component;

/**
 * Carries a trace across the transactional outbox. The request that queues an event stores its trace
 * context (W3C {@code traceparent}/{@code tracestate}) in the outbox row; the relay, running later on
 * a scheduler thread, continues that trace with a {@code outbox relay} span. The Kafka producer span
 * and its {@code traceparent} header then belong to the upload's trace, and so does everything the
 * worker does with the message.
 */
@Component
class OutboxTracing {

	private final Tracer tracer;

	private final Propagator propagator;

	OutboxTracing(Tracer tracer, Propagator propagator) {
		this.tracer = tracer;
		this.propagator = propagator;
	}

	/** The current trace context as propagation headers; empty outside a trace. */
	Map<String, String> currentContext() {
		Span span = this.tracer.currentSpan();
		if (span == null) {
			return Map.of();
		}
		Map<String, String> headers = new LinkedHashMap<>();
		this.propagator.inject(span.context(), headers, Map::put);
		return headers;
	}

	/**
	 * Starts the span that relays one event: a child of the stored context, or a new trace when the
	 * event has none. The caller puts it in scope while sending and ends it.
	 */
	Span startRelaySpan(Map<String, String> storedContext, String topic, String eventId, Instant queuedAt) {
		Span.Builder builder = storedContext.isEmpty() ? this.tracer.spanBuilder()
				: this.propagator.extract(storedContext, Map::get);
		Span span = builder.name("outbox relay")
			.tag("messaging.destination.name", topic)
			.tag("docquery.outbox.event_id", eventId)
			.start();
		if (queuedAt != null) {
			span.tag("docquery.outbox.wait_ms", Duration.between(queuedAt, Instant.now()).toMillis());
		}
		return span;
	}

	Tracer.SpanInScope inScope(Span span) {
		return this.tracer.withSpan(span);
	}

}
