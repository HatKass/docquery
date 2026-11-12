package dev.docquery.api.ingestion;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import dev.docquery.api.document.DocumentStatus;
import dev.docquery.api.document.DocumentStatusService;
import dev.docquery.api.ingestion.IngestionMessages.DocumentStatusChanged;

/**
 * Applies {@code document.status} events from the worker. {@link DocumentStatusService} ignores
 * repeated and out-of-order updates, so redelivered events are harmless; applied changes reach the
 * browser over the document event stream (SSE) after commit.
 * <p>
 * Malformed events are logged and skipped (retrying cannot fix them); database errors propagate and
 * are retried with backoff until the database is back (see {@link IngestionKafkaConfiguration}).
 */
@Component
class DocumentStatusListener {

	private static final Logger log = LoggerFactory.getLogger(DocumentStatusListener.class);

	private final JsonMapper json;

	private final DocumentStatusService statuses;

	DocumentStatusListener(JsonMapper json, DocumentStatusService statuses) {
		this.json = json;
		this.statuses = statuses;
	}

	/**
	 * Group = spring.kafka.consumer.group-id ({@code idIsGroup = false}). Not started by the one-off
	 * re-index command (docquery.reindex.run), which must not steal partitions.
	 */
	@KafkaListener(id = "document-status", idIsGroup = false, topics = "${docquery.ingestion.topics.status}",
			autoStartup = "#{!${docquery.reindex.run:false}}")
	void onStatus(ConsumerRecord<String, String> record) {
		DocumentStatusChanged event = parse(record);
		if (event == null) {
			return;
		}
		boolean changed = this.statuses.transition(event.documentId(), DocumentStatus.valueOf(event.status()),
				event.failureReason(), event.pageCount(), event.chunkCount());
		log.debug("Status {} for document {} {}", event.status(), event.documentId(), changed ? "applied" : "ignored");
	}

	private DocumentStatusChanged parse(ConsumerRecord<String, String> record) {
		try {
			DocumentStatusChanged event = (record.value() != null)
					? this.json.readValue(record.value(), DocumentStatusChanged.class) : null;
			if (event == null || event.documentId() == null || event.status() == null) {
				throw new IllegalArgumentException("required fields missing");
			}
			DocumentStatus.valueOf(event.status());
			return event;
		}
		catch (JacksonException | IllegalArgumentException ex) {
			log.warn("Skipping malformed document.status record at {}-{}@{}: {}", record.topic(), record.partition(),
					record.offset(), ex.getMessage());
			return null;
		}
	}

}
