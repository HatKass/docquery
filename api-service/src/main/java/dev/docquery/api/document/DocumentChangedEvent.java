package dev.docquery.api.document;

import java.util.UUID;

import dev.docquery.api.document.DocumentDtos.DocumentResponse;

/**
 * Published inside the transaction that changed a document; delivered to live streams only after that
 * transaction commits, so clients never see a change that was rolled back.
 *
 * @param document the document after the change, {@code null} for {@link Kind#DELETED}
 */
record DocumentChangedEvent(Kind kind, UUID workspaceId, UUID documentId, DocumentResponse document) {

	enum Kind {

		CREATED("document.created"), UPDATED("document.updated"), DELETED("document.deleted");

		private final String eventName;

		Kind(String eventName) {
			this.eventName = eventName;
		}

		String eventName() {
			return this.eventName;
		}

	}

	static DocumentChangedEvent created(DocumentResponse document) {
		return new DocumentChangedEvent(Kind.CREATED, document.workspaceId(), document.id(), document);
	}

	static DocumentChangedEvent updated(DocumentResponse document) {
		return new DocumentChangedEvent(Kind.UPDATED, document.workspaceId(), document.id(), document);
	}

	static DocumentChangedEvent deleted(UUID workspaceId, UUID documentId) {
		return new DocumentChangedEvent(Kind.DELETED, workspaceId, documentId, null);
	}

}
