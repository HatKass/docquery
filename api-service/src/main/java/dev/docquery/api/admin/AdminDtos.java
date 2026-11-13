package dev.docquery.api.admin;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.Min;

/** Request and response bodies of the admin API. */
public final class AdminDtos {

	private AdminDtos() {
	}

	/**
	 * Pipeline health at a glance.
	 *
	 * @param documentsByStatus document count per status, all workspaces
	 */
	public record Overview(Map<String, Long> documentsByStatus, long pendingDeadLetters, long users, long workspaces,
			int defaultWorkspaceLimit) {
	}

	/** A document whose ingestion failed, in any workspace. */
	public record FailedDocument(UUID documentId, UUID workspaceId, String workspaceName, String filename,
			String contentType, String failureReason, Instant failedAt, String uploadedBy) {
	}

	/**
	 * @param workspaceLimitOverride {@code null} when the default applies; 0 means no limit
	 * @param workspaceLimit the limit in effect; {@code null} when there is no limit
	 */
	public record AdminUser(UUID id, String username, String displayName, String email, Instant lastSeenAt,
			int workspacesCreated, Integer workspaceLimitOverride, Integer workspaceLimit, int questionsToday,
			int uploadsToday) {
	}

	/** @param limit {@code null} restores the default; 0 means no limit */
	public record WorkspaceLimitRequest(@Min(0) Integer limit) {
	}

}
