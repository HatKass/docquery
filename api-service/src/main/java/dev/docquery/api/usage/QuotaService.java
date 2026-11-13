package dev.docquery.api.usage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.docquery.api.common.error.ApiException;
import dev.docquery.api.usage.QuotaRepository.DailyCounts;
import dev.docquery.api.usage.QuotaRepository.WorkspaceAllowance;
import dev.docquery.api.usage.UsageDtos.MyQuotas;
import dev.docquery.api.usage.UsageDtos.QuotaStatus;

/**
 * Enforces the limits of {@link UsageProperties.Quotas}. Daily quotas count per UTC day.
 * <p>
 * The {@code count...} and {@code require...} methods must run inside the transaction of the action
 * they guard ({@link Propagation#MANDATORY}): the count is then committed exactly when the question,
 * upload or workspace is, and rolled back with it on failure. Each check is a single conditional
 * update or happens under a row lock, so concurrent requests can never exceed a limit.
 */
@Service
public class QuotaService {

	private final QuotaRepository repository;

	private final UsageProperties.Quotas quotas;

	private final Clock clock;

	@Autowired
	QuotaService(QuotaRepository repository, UsageProperties properties) {
		this(repository, properties, Clock.systemUTC());
	}

	QuotaService(QuotaRepository repository, UsageProperties properties, Clock clock) {
		this.repository = repository;
		this.quotas = properties.quotas();
		this.clock = clock;
	}

	/** Counts one question against the asker's daily quota, or throws 429 if it is used up. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void countQuestion(UUID userId) {
		int limit = this.quotas.dailyQuestionsPerUser();
		if (this.repository.incrementQuestions(userId, today(), limit).isEmpty()) {
			throw dailyQuotaExceeded("daily-question-quota-exceeded", "Daily question limit reached",
					"You have asked " + limit + " questions today, the daily limit.", limit);
		}
	}

	/** Cheap early check before an upload is stored; {@link #countUpload} is the binding one. */
	@Transactional(readOnly = true)
	public void checkUploadAllowed(UUID userId) {
		int limit = this.quotas.dailyUploadsPerUser();
		if (limit > 0 && this.repository.dailyCounts(userId, today()).uploads() >= limit) {
			throw uploadQuotaExceeded(limit);
		}
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void countUpload(UUID userId) {
		int limit = this.quotas.dailyUploadsPerUser();
		if (this.repository.incrementUploads(userId, today(), limit).isEmpty()) {
			throw uploadQuotaExceeded(limit);
		}
	}

	/** Throws 409 if the workspace already holds the maximum number of documents. */
	@Transactional(propagation = Propagation.MANDATORY)
	public void requireDocumentSlot(UUID workspaceId) {
		int limit = this.quotas.maxDocumentsPerWorkspace();
		if (limit == 0) {
			return;
		}
		int count = this.repository.lockAndCountDocuments(workspaceId);
		if (count >= limit) {
			ApiException ex = ApiException.conflict("workspace-document-limit-reached", "This workspace already holds "
					+ count + " documents, the maximum. Delete documents you no longer need to make room.");
			ex.getBody().setProperty("limit", limit);
			throw ex;
		}
	}

	/**
	 * Throws 409 if the user already created as many workspaces as they may (the admin override wins
	 * over the configured default). Deleting a workspace frees its slot; handing it over to another
	 * owner does not, so the limit cannot be bypassed by creating and abandoning workspaces.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void requireWorkspaceSlot(UUID userId) {
		WorkspaceAllowance allowance = this.repository.lockWorkspaceAllowance(userId);
		int limit = effectiveWorkspaceLimit(allowance);
		if (limit > 0 && allowance.created() >= limit) {
			ApiException ex = ApiException.conflict("workspace-limit-reached",
					"You can create up to " + limit + (limit == 1 ? " workspace" : " workspaces") + " and already have "
							+ allowance.created() + ". Delete one you no longer need, or ask an administrator to raise "
							+ "your limit.");
			ex.getBody().setProperty("limit", limit);
			ex.getBody().setProperty("used", allowance.created());
			throw ex;
		}
	}

	/** The caller's limits and what is left of them today (for the UI). */
	@Transactional(readOnly = true)
	public MyQuotas status(UUID userId) {
		DailyCounts counts = this.repository.dailyCounts(userId, today());
		WorkspaceAllowance allowance = this.repository.workspaceAllowance(userId);
		return new MyQuotas(QuotaStatus.of(counts.questions(), this.quotas.dailyQuestionsPerUser()),
				QuotaStatus.of(counts.uploads(), this.quotas.dailyUploadsPerUser()),
				QuotaStatus.of(allowance.created(), effectiveWorkspaceLimit(allowance)),
				this.quotas.maxDocumentsPerWorkspace() == 0 ? null : this.quotas.maxDocumentsPerWorkspace(),
				nextReset());
	}

	/** The configured workspace limit for users without an override (0 = no limit). */
	public int defaultWorkspaceLimit() {
		return this.quotas.maxWorkspacesPerUser();
	}

	/**
	 * Platform admin: overrides one user's workspace limit ({@code null} restores the default, 0 means
	 * no limit). Callers must have checked the admin role.
	 * @return whether the user exists
	 */
	@Transactional
	public boolean setWorkspaceLimitOverride(UUID userId, Integer limit) {
		if (limit != null && limit < 0) {
			throw ApiException.unprocessable("invalid-limit", "The limit must be 0 (no limit) or more.");
		}
		return this.repository.setWorkspaceLimitOverride(userId, limit);
	}

	int effectiveWorkspaceLimit(WorkspaceAllowance allowance) {
		return (allowance.override() != null) ? allowance.override() : this.quotas.maxWorkspacesPerUser();
	}

	/** The current UTC day, which daily quotas count against. */
	public LocalDate today() {
		return LocalDate.now(this.clock.withZone(ZoneOffset.UTC));
	}

	Instant nextReset() {
		return today().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
	}

	private ApiException uploadQuotaExceeded(int limit) {
		return dailyQuotaExceeded("daily-upload-quota-exceeded", "Daily upload limit reached",
				"You have uploaded " + limit + " documents today, the daily limit.", limit);
	}

	private ApiException dailyQuotaExceeded(String code, String title, String reached, int limit) {
		Instant reset = nextReset();
		Duration wait = Duration.between(this.clock.instant(), reset);
		ApiException ex = new ApiException(HttpStatus.TOO_MANY_REQUESTS, code, title,
				reached + " It resets at midnight UTC (in " + humanize(wait) + ").");
		ex.getBody().setProperty("limit", limit);
		ex.getBody().setProperty("resetsAt", reset.toString());
		ex.getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, wait.toSeconds())));
		return ex;
	}

	static String humanize(Duration duration) {
		long minutes = Math.max(1, (duration.toSeconds() + 59) / 60);
		long hours = minutes / 60;
		long rest = minutes % 60;
		if (hours == 0) {
			return rest + " min";
		}
		return (rest == 0) ? hours + " h" : hours + " h " + rest + " min";
	}

}
