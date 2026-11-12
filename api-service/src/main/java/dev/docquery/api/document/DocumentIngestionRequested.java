package dev.docquery.api.document;

import java.util.UUID;

/**
 * A document needs (re-)indexing. Published <em>inside</em> the transaction that created or reset
 * the document; the ingestion feature turns it into an outbox row in that same transaction, so the
 * request is committed atomically with the change that caused it.
 */
public record DocumentIngestionRequested(UUID documentId, UUID workspaceId, String storageKey, String contentType,
		String filename, UUID uploadedBy, Reason reason) {

	public enum Reason {

		/** First indexing after an upload. */
		UPLOAD,

		/** Requested again: retry after a failure, or a new embedding model. */
		REINDEX,

		/** Still queued long after upload: the original request was lost or never created. */
		REQUEUE

	}

}
