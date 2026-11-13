package dev.docquery.worker.ingestion;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;

import dev.docquery.worker.extraction.ExtractionException;
import dev.docquery.worker.ingestion.IngestionMessages.DocumentUploaded;
import dev.docquery.worker.ingestion.IngestionMessages.MalformedMessageException;
import dev.docquery.worker.storage.DocumentStorage.StoredFileMissingException;

/**
 * Last stop for an event that could not be processed (permanent error, or retries exhausted):
 * <ol>
 * <li>records the failure, so a redelivery does not start over;</li>
 * <li>marks the document FAILED with a reason a user can act on (internals stay in the logs);</li>
 * <li>publishes the original record to the dead-letter topic, with the exception in its headers,
 * for inspection and replay (admin page, Phase 6).</li>
 * </ol>
 * If any step throws (e.g. Kafka or the database is down), the error handler redelivers the record
 * later, so a failure is never silently dropped.
 */
class IngestionFailureRecoverer implements ConsumerRecordRecoverer {

	private static final Logger log = LoggerFactory.getLogger(IngestionFailureRecoverer.class);

	private final IngestionMessages messages;

	private final IngestionRepository repository;

	private final StatusPublisher status;

	private final DeadLetterPublishingRecoverer deadLetter;

	private final int maxAttempts;

	private final IngestionMetrics metrics;

	IngestionFailureRecoverer(IngestionMessages messages, IngestionRepository repository, StatusPublisher status,
			KafkaOperations<?, ?> kafka, IngestionProperties properties, IngestionMetrics metrics) {
		this.metrics = metrics;
		this.messages = messages;
		this.repository = repository;
		this.status = status;
		this.maxAttempts = properties.retry().maxAttempts();
		String deadLetterTopic = properties.topics().deadLetter();
		// Partition -1: let the producer choose, so the DLT does not need the source's partition count.
		this.deadLetter = new DeadLetterPublishingRecoverer(kafka,
				(record, ex) -> new TopicPartition(deadLetterTopic, -1));
	}

	@Override
	public void accept(ConsumerRecord<?, ?> record, Exception exception) {
		Throwable cause = rootCause(exception);
		DocumentUploaded event = parseQuietly(record);
		if (event != null) {
			String reason = userFacingReason(cause);
			log.error("Ingestion of document {} (event {}) failed: {}", event.documentId(), event.eventId(), reason,
					exception);
			this.repository.recordFailure(event, reason);
			this.status.publishFailed(event, reason);
			this.metrics.failed(event);
		}
		else {
			log.error("Unprocessable record at {}-{}@{} sent to the dead-letter topic", record.topic(),
					record.partition(), record.offset(), exception);
		}
		this.deadLetter.accept(record, exception);
	}

	String userFacingReason(Throwable cause) {
		if (cause instanceof ExtractionException) {
			return cause.getMessage();
		}
		if (cause instanceof StoredFileMissingException) {
			return "The stored file is missing. Delete the document and upload it again.";
		}
		if (cause instanceof EmbeddingUnavailableException) {
			return "The embedding service was unavailable (tried " + this.maxAttempts
					+ " times). Try again later with \"Retry\".";
		}
		return "An internal error stopped processing (tried " + this.maxAttempts
				+ " times). Try again later with \"Retry\".";
	}

	/** The first exception in the chain that the pipeline itself threw (Kafka wraps it). */
	private static Throwable rootCause(Throwable exception) {
		for (Throwable t = exception; t != null; t = t.getCause()) {
			if (t instanceof ExtractionException || t instanceof StoredFileMissingException
					|| t instanceof EmbeddingUnavailableException || t instanceof MalformedMessageException) {
				return t;
			}
		}
		return exception;
	}

	private DocumentUploaded parseQuietly(ConsumerRecord<?, ?> record) {
		try {
			return (record.value() instanceof String value) ? this.messages.readUploaded(value) : null;
		}
		catch (MalformedMessageException ex) {
			return null;
		}
	}

}
