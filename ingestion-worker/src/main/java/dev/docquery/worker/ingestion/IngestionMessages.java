package dev.docquery.worker.ingestion;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON messages exchanged with api-service over Kafka. Each service declares its own copy of
 * these records (no shared library), so the topics are the contract; unknown fields are ignored,
 * which lets either side add fields without breaking the other. Messages are keyed by document id,
 * so all events of one document stay in order on one partition.
 */
@Component
public class IngestionMessages {

	/**
	 * {@code document.uploaded}: index (or re-index) this document.
	 *
	 * @param eventId unique per request; the idempotency key
	 * @param reason {@code UPLOAD} or {@code REINDEX}
	 */
	public record DocumentUploaded(UUID eventId, UUID documentId, UUID workspaceId, String storageKey,
			String contentType, String filename, UUID uploadedBy, String reason, Instant occurredAt) {
	}

	/**
	 * {@code document.status}: the pipeline reached a new status.
	 *
	 * @param sourceEventId the {@link DocumentUploaded#eventId()} being processed
	 * @param failureReason human-readable, only for FAILED
	 */
	public record DocumentStatusChanged(UUID eventId, UUID sourceEventId, UUID documentId, UUID workspaceId,
			String status, String failureReason, Integer pageCount, Integer chunkCount, Instant occurredAt) {
	}

	/** A message that can never be processed (not JSON, required fields missing...). */
	public static class MalformedMessageException extends RuntimeException {

		MalformedMessageException(String message, Throwable cause) {
			super(message, cause);
		}

	}

	private final JsonMapper json;

	IngestionMessages(JsonMapper json) {
		this.json = json;
	}

	public DocumentUploaded readUploaded(String value) {
		DocumentUploaded message;
		try {
			message = (value != null) ? this.json.readValue(value, DocumentUploaded.class) : null;
		}
		catch (JacksonException ex) {
			throw new MalformedMessageException("document.uploaded message is not valid JSON", ex);
		}
		if (message == null || message.eventId() == null || message.documentId() == null
				|| message.workspaceId() == null || message.storageKey() == null || message.contentType() == null) {
			throw new MalformedMessageException("document.uploaded message lacks required fields", null);
		}
		return message;
	}

	public String write(Object message) {
		return this.json.writeValueAsString(message);
	}

}
