package dev.docquery.api.document;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a document is in the ingestion pipeline:
 *
 * <pre>
 * UPLOADED → EXTRACTING → CHUNKING → EMBEDDING → READY
 *     └──────────┴────────────┴───────────┴──────→ FAILED (with a reason)
 * READY / FAILED → UPLOADED   (re-index, or retry after a failure)
 * </pre>
 *
 * Steps only move forward, so a late or replayed status event can never move a document backwards
 * (e.g. a duplicate {@code EXTRACTING} arriving after {@code READY} is ignored).
 */
public enum DocumentStatus {

	UPLOADED, EXTRACTING, CHUNKING, EMBEDDING, READY, FAILED;

	/** Pipeline finished, successfully or not. */
	public boolean isTerminal() {
		return this == READY || this == FAILED;
	}

	public boolean canTransitionTo(DocumentStatus next) {
		return allowedNext().contains(next);
	}

	private Set<DocumentStatus> allowedNext() {
		return switch (this) {
			case UPLOADED -> EnumSet.of(EXTRACTING, FAILED);
			case EXTRACTING -> EnumSet.of(CHUNKING, FAILED);
			case CHUNKING -> EnumSet.of(EMBEDDING, FAILED);
			case EMBEDDING -> EnumSet.of(READY, FAILED);
			case READY, FAILED -> EnumSet.of(UPLOADED);
		};
	}

}
