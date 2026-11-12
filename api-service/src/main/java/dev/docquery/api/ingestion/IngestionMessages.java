package dev.docquery.api.ingestion;

import java.time.Instant;
import java.util.UUID;

/**
 * JSON messages exchanged with the ingestion worker. The worker declares its own copy (no shared
 * library): the topics are the contract, and unknown fields are ignored on both sides, so either
 * service can add fields without breaking the other. Messages are keyed by document id, which keeps
 * all events of one document in order on one partition.
 */
final class IngestionMessages {

	private IngestionMessages() {
	}

	/**
	 * {@code document.uploaded}: index (or re-index) this document.
	 *
	 * @param eventId unique per request; the worker's idempotency key
	 * @param reason {@code UPLOAD} or {@code REINDEX}
	 */
	record DocumentUploaded(UUID eventId, UUID documentId, UUID workspaceId, String storageKey, String contentType,
			String filename, UUID uploadedBy, String reason, Instant occurredAt) {
	}

	/**
	 * {@code document.status}: the pipeline reached a new status.
	 *
	 * @param sourceEventId the {@link DocumentUploaded#eventId()} being processed
	 * @param failureReason human-readable, only for FAILED
	 */
	record DocumentStatusChanged(UUID eventId, UUID sourceEventId, UUID documentId, UUID workspaceId, String status,
			String failureReason, Integer pageCount, Integer chunkCount, Instant occurredAt) {
	}

}
