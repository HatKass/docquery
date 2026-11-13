package dev.docquery.api.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The transactional outbox table: events waiting to be relayed to Kafka. */
@Repository
class OutboxRepository {

	/** @param traceContext propagation headers of the request that queued the event (may be empty) */
	record OutboxEvent(UUID id, String topic, String key, String payload, Map<String, String> traceContext,
			Instant createdAt) {
	}

	private static final TypeReference<Map<String, String>> HEADERS = new TypeReference<>() {
	};

	private static final int MAX_ERROR_LENGTH = 1000;

	private final JdbcClient jdbc;

	private final JsonMapper json;

	OutboxRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	/**
	 * Must run inside the transaction of the change that caused the event.
	 *
	 * @param traceContext stored with the event so the relay continues the caller's trace
	 */
	void add(UUID id, String topic, String key, String payload, Map<String, String> traceContext) {
		this.jdbc.sql("""
				INSERT INTO outbox_event (id, topic, message_key, payload, trace_headers)
				VALUES (:id, :topic, :key, CAST(:payload AS jsonb), CAST(:traceHeaders AS jsonb))
				""")
			.param("id", id)
			.param("topic", topic)
			.param("key", key)
			.param("payload", payload)
			.param("traceHeaders", traceContext.isEmpty() ? null : this.json.writeValueAsString(traceContext))
			.update();
	}

	/**
	 * Oldest events first, row-locked for the current transaction. {@code SKIP LOCKED} lets several
	 * api-service replicas relay concurrently without sending an event twice.
	 */
	List<OutboxEvent> lockOldest(int limit) {
		return this.jdbc.sql("""
				SELECT id, topic, message_key, payload::text AS payload, trace_headers::text AS trace_headers, created_at
				  FROM outbox_event
				 ORDER BY created_at, id
				 LIMIT :limit
				   FOR UPDATE SKIP LOCKED
				""")
			.param("limit", limit)
			.query((rs, i) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("topic"),
					rs.getString("message_key"), rs.getString("payload"), traceContext(rs.getString("trace_headers")),
					rs.getTimestamp("created_at").toInstant()))
			.list();
	}

	private Map<String, String> traceContext(String headers) {
		return (headers != null) ? this.json.readValue(headers, HEADERS) : Map.of();
	}

	void delete(UUID id) {
		this.jdbc.sql("DELETE FROM outbox_event WHERE id = :id").param("id", id).update();
	}

	void recordFailure(UUID id, String error) {
		String message = (error == null) ? "unknown error"
				: error.substring(0, Math.min(error.length(), MAX_ERROR_LENGTH));
		this.jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1, last_error = :error WHERE id = :id")
			.param("id", id)
			.param("error", message)
			.update();
	}

	int count() {
		return this.jdbc.sql("SELECT count(*) FROM outbox_event").query(Integer.class).single();
	}

	/** Seconds the oldest waiting event has been waiting; 0 when the outbox is empty. */
	double oldestAgeSeconds() {
		return this.jdbc
			.sql("SELECT coalesce(extract(epoch FROM now() - min(created_at)), 0)::float8 FROM outbox_event")
			.query(Double.class)
			.single();
	}

}
