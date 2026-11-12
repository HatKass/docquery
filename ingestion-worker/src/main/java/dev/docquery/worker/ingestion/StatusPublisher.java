package dev.docquery.worker.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import dev.docquery.worker.ingestion.IngestionMessages.DocumentStatusChanged;
import dev.docquery.worker.ingestion.IngestionMessages.DocumentUploaded;

/**
 * Publishes {@code document.status} events, keyed by document id. Sends are synchronous: the
 * pipeline only moves on once Kafka acknowledged the previous status, so statuses of a document are
 * delivered in order, and a broker outage surfaces as a (retryable) failure.
 */
@Component
class StatusPublisher {

	static final String EXTRACTING = "EXTRACTING";

	static final String CHUNKING = "CHUNKING";

	static final String EMBEDDING = "EMBEDDING";

	static final String READY = "READY";

	static final String FAILED = "FAILED";

	private static final Duration SEND_TIMEOUT = Duration.ofSeconds(15);

	private final KafkaTemplate<String, String> kafka;

	private final IngestionMessages messages;

	private final String topic;

	StatusPublisher(KafkaTemplate<String, String> kafka, IngestionMessages messages, IngestionProperties properties) {
		this.kafka = kafka;
		this.messages = messages;
		this.topic = properties.topics().status();
	}

	void publish(DocumentUploaded source, String status, Integer pageCount, Integer chunkCount) {
		send(source, status, null, pageCount, chunkCount);
	}

	void publishFailed(DocumentUploaded source, String reason) {
		send(source, FAILED, reason, null, null);
	}

	private void send(DocumentUploaded source, String status, String reason, Integer pageCount, Integer chunkCount) {
		DocumentStatusChanged message = new DocumentStatusChanged(UUID.randomUUID(), source.eventId(),
				source.documentId(), source.workspaceId(), status, reason, pageCount, chunkCount, Instant.now());
		try {
			this.kafka.send(this.topic, source.documentId().toString(), this.messages.write(message))
				.get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while publishing status " + status, ex);
		}
		catch (ExecutionException | TimeoutException ex) {
			throw new IllegalStateException("Could not publish status " + status + " for document "
					+ source.documentId(), ex);
		}
	}

}
