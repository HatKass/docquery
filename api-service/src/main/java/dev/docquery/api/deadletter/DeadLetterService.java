package dev.docquery.api.deadletter;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.docquery.api.common.error.ApiException;
import dev.docquery.api.deadletter.DeadLetterDtos.DeadLetterResponse;
import dev.docquery.api.deadletter.DeadLetterDtos.DeadLetterStatus;
import dev.docquery.api.document.DocumentService;

/**
 * Admin actions on dead-lettered ingestion events. Callers must have checked the admin role.
 * <p>
 * "Retry" does not replay the original Kafka record: the worker already recorded that event id as
 * processed (FAILED) and would only re-announce the failure. It queues the document for ingestion
 * again with a <em>new</em> event, exactly like a member's "Retry" in the document library.
 */
@Service
public class DeadLetterService {

	private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

	private final DeadLetterRepository repository;

	private final DocumentService documents;

	DeadLetterService(DeadLetterRepository repository, DocumentService documents) {
		this.repository = repository;
		this.documents = documents;
	}

	public List<DeadLetterResponse> list(DeadLetterStatus status, int limit) {
		return this.repository.findAll(status, Math.clamp(limit, 1, 500));
	}

	public int countPending() {
		return this.repository.countPending();
	}

	public DeadLetterResponse retry(UUID id, UUID adminId) {
		DeadLetterResponse event = requirePending(id);
		if (event.documentId() == null) {
			throw ApiException.conflict("dead-letter-not-retryable", "This event could not be read as a "
					+ "document.uploaded event, so there is no document to retry. Inspect it, then dismiss it.");
		}
		if (event.documentStatus() == null) {
			throw ApiException.conflict("dead-letter-not-retryable",
					"The document of this event has been deleted; there is nothing to retry. Dismiss the event.");
		}
		// Own transaction; 409 "document-busy" if the document is already being processed again.
		this.documents.reindexAsAdmin(event.documentId());
		this.repository.resolve(id, DeadLetterStatus.RETRIED, adminId);
		log.info("Admin {} retried dead-lettered event {} (document {})", adminId, id, event.documentId());
		return this.repository.find(id).orElseThrow();
	}

	public DeadLetterResponse dismiss(UUID id, UUID adminId) {
		requirePending(id);
		if (!this.repository.resolve(id, DeadLetterStatus.DISMISSED, adminId)) {
			throw alreadyResolved();
		}
		return this.repository.find(id).orElseThrow();
	}

	private DeadLetterResponse requirePending(UUID id) {
		DeadLetterResponse event = this.repository.find(id)
			.orElseThrow(
					() -> ApiException.notFound("dead-letter-not-found", "Dead-letter event " + id + " was not found."));
		if (event.status() != DeadLetterStatus.PENDING) {
			throw alreadyResolved();
		}
		return event;
	}

	private static ApiException alreadyResolved() {
		return ApiException.conflict("dead-letter-resolved", "This event has already been retried or dismissed.");
	}

}
