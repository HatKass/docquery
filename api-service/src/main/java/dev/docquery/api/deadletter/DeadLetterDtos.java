package dev.docquery.api.deadletter;

import java.time.Instant;
import java.util.UUID;

/** Response bodies of the dead-letter part of the admin API. */
public final class DeadLetterDtos {

	private DeadLetterDtos() {
	}

	public enum DeadLetterStatus {

		/** Waiting for an admin. */
		PENDING,
		/** The document was queued for ingestion again. */
		RETRIED,
		/** Inspected and set aside without a retry. */
		DISMISSED

	}

	/**
	 * One {@code document.uploaded} event the worker gave up on.
	 *
	 * @param documentId {@code null} when the payload could not be read as an event
	 * @param documentName {@code null} when the document no longer exists
	 * @param documentStatus the document's current status, {@code null} when it no longer exists
	 * @param workspaceName {@code null} when the workspace no longer exists
	 * @param errorSummary the root cause in plain words (framework wrappers removed), for the list view
	 * @param exceptionMessage the worker's full error (admin-only: may contain internals), for the details
	 * @param retryable whether "retry" can succeed now (pending, and the document still exists)
	 */
	public record DeadLetterResponse(UUID id, DeadLetterStatus status, UUID eventId, UUID documentId,
			String documentName, String documentStatus, UUID workspaceId, String workspaceName, String errorSummary,
			String exceptionClass, String exceptionMessage, String originalTopic, Integer originalPartition, Long originalOffset,
			Instant failedAt, Instant resolvedAt, String resolvedBy, boolean retryable, String payload) {
	}

}
