package dev.docquery.api.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Response bodies of the document API. Database rows never leave the service layer. */
public final class DocumentDtos {

	private DocumentDtos() {
	}

	/**
	 * Document metadata as shown in the library.
	 *
	 * @param failureReason human-readable reason, only when {@code status} is FAILED
	 * @param pageCount known once text extraction finished, otherwise {@code null}
	 * @param chunkCount known once chunking finished, otherwise {@code null}
	 * @param uploadedBy uploader's id, {@code null} if that user no longer exists
	 */
	public record DocumentResponse(UUID id, UUID workspaceId, String filename, String contentType, long sizeBytes,
			DocumentStatus status, String failureReason, Integer pageCount, Integer chunkCount, UUID uploadedBy,
			String uploadedByName, Instant uploadedAt, Instant statusChangedAt) {
	}

	/** Limits the UI checks before uploading, so users get instant feedback (the API re-checks). */
	public record UploadPolicyResponse(long maxFileSizeBytes, List<String> allowedExtensions,
			List<String> allowedContentTypes) {
	}

	/**
	 * Payload of a live document event (SSE). {@code document} is absent for deletions.
	 */
	public record DocumentEventPayload(UUID documentId, DocumentResponse document) {
	}

}
