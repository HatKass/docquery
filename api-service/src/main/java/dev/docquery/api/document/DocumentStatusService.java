package dev.docquery.api.document;

import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import dev.docquery.api.document.DocumentRepository.StatusRow;

/**
 * Applies pipeline status changes to documents and pushes them to live streams. This is the single
 * entry point for status updates; in Phase 4 the {@code document.status} Kafka consumer calls it.
 * <p>
 * Idempotent and order-safe: a repeated or out-of-order update (see {@link DocumentStatus}) is
 * ignored and reported as {@code false}, so consumers can process the same event twice safely.
 */
@Service
public class DocumentStatusService {

	private static final Logger log = LoggerFactory.getLogger(DocumentStatusService.class);

	static final int MAX_FAILURE_REASON_LENGTH = 1000;

	private final DocumentRepository documents;

	private final ApplicationEventPublisher events;

	private final MeterRegistry meters;

	DocumentStatusService(DocumentRepository documents, ApplicationEventPublisher events, MeterRegistry meters) {
		this.documents = documents;
		this.events = events;
		this.meters = meters;
	}

	/**
	 * @param failureReason required for FAILED, ignored otherwise
	 * @param pageCount set when known (after extraction), otherwise {@code null} keeps the stored value
	 * @param chunkCount set when known (after chunking), otherwise {@code null} keeps the stored value
	 * @return whether the document changed
	 */
	@Transactional
	public boolean transition(UUID documentId, DocumentStatus next, String failureReason, Integer pageCount,
			Integer chunkCount) {
		StatusRow current = this.documents.lockStatus(documentId).orElse(null);
		if (current == null) {
			log.debug("Ignoring status {} for document {}: it no longer exists", next, documentId);
			return false;
		}
		if (!current.status().canTransitionTo(next)) {
			log.debug("Ignoring status {} for document {}: currently {}", next, documentId, current.status());
			return false;
		}
		String reason = (next == DocumentStatus.FAILED) ? normalizeReason(failureReason) : null;
		this.documents.updateStatus(documentId, next, reason, pageCount, chunkCount);
		Counter.builder("docquery.documents.status.changes")
			.description("Document status changes applied (READY and FAILED are ingestion outcomes)")
			.tag("status", next.name())
			.register(this.meters)
			.increment();
		this.documents.find(current.workspaceId(), documentId)
			.ifPresent(updated -> this.events.publishEvent(DocumentChangedEvent.updated(updated)));
		return true;
	}

	private static String normalizeReason(String reason) {
		if (!StringUtils.hasText(reason)) {
			return "Processing failed for an unknown reason.";
		}
		String stripped = reason.strip();
		return (stripped.length() <= MAX_FAILURE_REASON_LENGTH) ? stripped
				: stripped.substring(0, MAX_FAILURE_REASON_LENGTH - 1) + "…";
	}

}
