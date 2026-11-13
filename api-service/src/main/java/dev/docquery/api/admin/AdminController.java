package dev.docquery.api.admin;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.docquery.api.admin.AdminDtos.AdminUser;
import dev.docquery.api.admin.AdminDtos.FailedDocument;
import dev.docquery.api.admin.AdminDtos.Overview;
import dev.docquery.api.admin.AdminDtos.WorkspaceLimitRequest;
import dev.docquery.api.common.error.ApiException;
import dev.docquery.api.deadletter.DeadLetterDtos.DeadLetterResponse;
import dev.docquery.api.deadletter.DeadLetterDtos.DeadLetterStatus;
import dev.docquery.api.deadletter.DeadLetterService;
import dev.docquery.api.document.DocumentDtos.DocumentResponse;
import dev.docquery.api.document.DocumentService;
import dev.docquery.api.usage.QuotaService;
import dev.docquery.api.usage.UsageDtos.UsageReport;
import dev.docquery.api.usage.UsageReportService;
import dev.docquery.api.user.CurrentUser;

/**
 * Platform administration. Every endpoint requires the admin role, enforced once for
 * {@code /api/admin/**} in {@code SecurityConfiguration} (403 for everyone else).
 */
@RestController
@RequestMapping("/api/admin")
class AdminController {

	private final AdminRepository admin;

	private final UsageReportService usage;

	private final DeadLetterService deadLetters;

	private final DocumentService documents;

	private final QuotaService quotas;

	AdminController(AdminRepository admin, UsageReportService usage, DeadLetterService deadLetters,
			DocumentService documents, QuotaService quotas) {
		this.admin = admin;
		this.usage = usage;
		this.deadLetters = deadLetters;
		this.documents = documents;
		this.quotas = quotas;
	}

	@GetMapping("/overview")
	Overview overview() {
		return new Overview(this.admin.documentsByStatus(), this.deadLetters.countPending(),
				this.admin.count("app_user"), this.admin.count("workspace"), this.quotas.defaultWorkspaceLimit());
	}

	/** Tokens and estimated cost of the whole platform, deleted workspaces included. */
	@GetMapping("/usage")
	UsageReport usage(@RequestParam(defaultValue = "30") int days) {
		return this.usage.platformReport(days);
	}

	@GetMapping("/failures")
	List<FailedDocument> failures(@RequestParam(defaultValue = "50") int limit) {
		return this.admin.failedDocuments(Math.clamp(limit, 1, 500));
	}

	/** Queues a document of any workspace for ingestion again. */
	@PostMapping("/documents/{documentId}/reindex")
	ResponseEntity<DocumentResponse> reindex(@PathVariable UUID documentId) {
		return ResponseEntity.accepted().body(this.documents.reindexAsAdmin(documentId));
	}

	/** @param status PENDING (default), RETRIED, DISMISSED or ALL */
	@GetMapping("/dead-letters")
	List<DeadLetterResponse> deadLetters(@RequestParam(defaultValue = "PENDING") String status,
			@RequestParam(defaultValue = "100") int limit) {
		DeadLetterStatus filter;
		try {
			filter = "ALL".equalsIgnoreCase(status) ? null : DeadLetterStatus.valueOf(status.toUpperCase());
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.unprocessable("invalid-status", "status must be PENDING, RETRIED, DISMISSED or ALL.");
		}
		return this.deadLetters.list(filter, limit);
	}

	@PostMapping("/dead-letters/{id}/retry")
	ResponseEntity<DeadLetterResponse> retry(@PathVariable UUID id, CurrentUser user) {
		return ResponseEntity.accepted().body(this.deadLetters.retry(id, user.id()));
	}

	@PostMapping("/dead-letters/{id}/dismiss")
	DeadLetterResponse dismiss(@PathVariable UUID id, CurrentUser user) {
		return this.deadLetters.dismiss(id, user.id());
	}

	@GetMapping("/users")
	List<AdminUser> users(@RequestParam(name = "q", required = false) String query,
			@RequestParam(defaultValue = "50") int limit) {
		return this.admin.users(query, this.quotas.today(), this.quotas.defaultWorkspaceLimit(),
				Math.clamp(limit, 1, 200));
	}

	/** Overrides how many workspaces one user may create ({@code null} restores the default). */
	@PutMapping("/users/{userId}/workspace-limit")
	AdminUser setWorkspaceLimit(@PathVariable UUID userId, @Valid @RequestBody WorkspaceLimitRequest request) {
		if (!this.quotas.setWorkspaceLimitOverride(userId, request.limit())) {
			throw ApiException.notFound("user-not-found", "User " + userId + " was not found.");
		}
		return this.admin.user(userId, this.quotas.today(), this.quotas.defaultWorkspaceLimit()).orElseThrow();
	}

}
