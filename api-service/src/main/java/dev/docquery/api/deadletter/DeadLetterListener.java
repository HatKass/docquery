package dev.docquery.api.deadletter;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import dev.docquery.api.deadletter.DeadLetterRepository.NewDeadLetter;

/**
 * Copies records from the dead-letter topic into {@code dead_letter_event}, where the admin page lists
 * them. The topic keeps the records; the table adds what a topic cannot: a status (pending, retried,
 * dismissed), who resolved it, and joins to the document and workspace.
 * <p>
 * Idempotent: the record's own topic, partition and offset are unique in the table. Every record is
 * stored, even one whose payload cannot be parsed (it is shown raw). Database errors propagate and
 * are retried by the container's error handler (see {@code IngestionKafkaConfiguration}).
 */
@Component
class DeadLetterListener {

	private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

	static final int MAX_TEXT = 4000;

	static final int MAX_PAYLOAD = 100_000;

	private final DeadLetterRepository repository;

	private final JsonMapper json;

	DeadLetterListener(DeadLetterRepository repository, JsonMapper json) {
		this.repository = repository;
		this.json = json;
	}

	/** Not started by the one-off re-index command, like the status listener. */
	@KafkaListener(id = "document-dead-letter", idIsGroup = false, topics = "${docquery.ingestion.topics.dead-letter}",
			autoStartup = "#{!${docquery.reindex.run:false}}")
	void onDeadLetter(ConsumerRecord<String, String> record) {
		JsonNode payload = parse(record.value());
		UUID documentId = uuid(payload, "documentId");
		boolean stored = this.repository.insert(new NewDeadLetter(record.topic(), record.partition(), record.offset(),
				text(record, KafkaHeaders.DLT_ORIGINAL_TOPIC), intHeader(record, KafkaHeaders.DLT_ORIGINAL_PARTITION),
				longHeader(record, KafkaHeaders.DLT_ORIGINAL_OFFSET), record.key(), truncate(record.value(), MAX_PAYLOAD),
				uuid(payload, "eventId"), documentId, uuid(payload, "workspaceId"),
				truncate(text(record, KafkaHeaders.DLT_EXCEPTION_FQCN), MAX_TEXT),
				truncate(text(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE), MAX_TEXT), failedAt(record)));
		if (stored) {
			log.warn("Dead-lettered ingestion event {}-{}@{} (document {}) recorded for review", record.topic(),
					record.partition(), record.offset(), documentId);
		}
	}

	private JsonNode parse(String value) {
		if (value == null) {
			return null;
		}
		try {
			return this.json.readTree(value);
		}
		catch (JacksonException ex) {
			return null;
		}
	}

	private static UUID uuid(JsonNode payload, String field) {
		if (payload == null || !payload.isObject() || !payload.hasNonNull(field)) {
			return null;
		}
		try {
			return UUID.fromString(payload.get(field).asString());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/** The DLT record is written when the worker gives up, so its timestamp is the failure time. */
	private static Instant failedAt(ConsumerRecord<?, ?> record) {
		return (record.timestamp() > 0) ? Instant.ofEpochMilli(record.timestamp()) : Instant.now();
	}

	private static String text(ConsumerRecord<?, ?> record, String name) {
		Header header = record.headers().lastHeader(name);
		return (header != null && header.value() != null) ? new String(header.value(), StandardCharsets.UTF_8) : null;
	}

	private static Integer intHeader(ConsumerRecord<?, ?> record, String name) {
		Header header = record.headers().lastHeader(name);
		return (header != null && header.value() != null && header.value().length == Integer.BYTES)
				? ByteBuffer.wrap(header.value()).getInt() : null;
	}

	private static Long longHeader(ConsumerRecord<?, ?> record, String name) {
		Header header = record.headers().lastHeader(name);
		return (header != null && header.value() != null && header.value().length == Long.BYTES)
				? ByteBuffer.wrap(header.value()).getLong() : null;
	}

	private static String truncate(String value, int max) {
		return (value == null || value.length() <= max) ? value : value.substring(0, max) + "...";
	}

}
