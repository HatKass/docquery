package dev.docquery.api.ingestion;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The transactional outbox table: events waiting to be relayed to Kafka. */
@Repository
class OutboxRepository {

	record OutboxEvent(UUID id, String topic, String key, String payload) {
	}

	private static final int MAX_ERROR_LENGTH = 1000;

	private final JdbcClient jdbc;

	OutboxRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Must run inside the transaction of the change that caused the event. */
	void add(UUID id, String topic, String key, String payload) {
		this.jdbc.sql("""
				INSERT INTO outbox_event (id, topic, message_key, payload)
				VALUES (:id, :topic, :key, CAST(:payload AS jsonb))
				""").param("id", id).param("topic", topic).param("key", key).param("payload", payload).update();
	}

	/**
	 * Oldest events first, row-locked for the current transaction. {@code SKIP LOCKED} lets several
	 * api-service replicas relay concurrently without sending an event twice.
	 */
	List<OutboxEvent> lockOldest(int limit) {
		return this.jdbc.sql("""
				SELECT id, topic, message_key, payload::text AS payload
				  FROM outbox_event
				 ORDER BY created_at, id
				 LIMIT :limit
				   FOR UPDATE SKIP LOCKED
				""")
			.param("limit", limit)
			.query((rs, i) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("topic"),
					rs.getString("message_key"), rs.getString("payload")))
			.list();
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

}
