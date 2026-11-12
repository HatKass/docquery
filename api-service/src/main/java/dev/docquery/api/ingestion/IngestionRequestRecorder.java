package dev.docquery.api.ingestion;

import java.time.Instant;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import dev.docquery.api.document.DocumentIngestionRequested;
import dev.docquery.api.ingestion.IngestionMessages.DocumentUploaded;

/**
 * Turns an ingestion request into an outbox row. A plain (synchronous) {@link EventListener} on
 * purpose: it runs inside the publisher's transaction, so the upload or re-index and its
 * {@code document.uploaded} event commit or roll back together.
 */
@Component
class IngestionRequestRecorder {

	private final OutboxRepository outbox;

	private final JsonMapper json;

	private final String topic;

	IngestionRequestRecorder(OutboxRepository outbox, JsonMapper json, IngestionProperties properties) {
		this.outbox = outbox;
		this.json = json;
		this.topic = properties.topics().uploaded();
	}

	@EventListener
	void onIngestionRequested(DocumentIngestionRequested request) {
		UUID eventId = UUID.randomUUID();
		DocumentUploaded message = new DocumentUploaded(eventId, request.documentId(), request.workspaceId(),
				request.storageKey(), request.contentType(), request.filename(), request.uploadedBy(),
				request.reason().name(), Instant.now());
		this.outbox.add(eventId, this.topic, request.documentId().toString(), this.json.writeValueAsString(message));
	}

}
