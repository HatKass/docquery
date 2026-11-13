package dev.docquery.api.usage;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response bodies of the usage API. Costs are estimates in USD, see {@link PriceTable}. */
public final class UsageDtos {

	private UsageDtos() {
	}

	/**
	 * @param limit {@code null} when there is no limit
	 * @param remaining {@code null} when there is no limit
	 */
	public record QuotaStatus(int used, Integer limit, Integer remaining) {

		static QuotaStatus of(int used, int limit) {
			return (limit == 0) ? new QuotaStatus(used, null, null)
					: new QuotaStatus(used, limit, Math.max(0, limit - used));
		}

	}

	/**
	 * The caller's own limits.
	 *
	 * @param maxDocumentsPerWorkspace {@code null} when there is no limit
	 * @param resetsAt when the daily counters start again (midnight UTC)
	 */
	public record MyQuotas(QuotaStatus questionsToday, QuotaStatus uploadsToday, QuotaStatus workspaces,
			Integer maxDocumentsPerWorkspace, Instant resetsAt) {
	}

	/**
	 * Usage over a period (inclusive UTC days).
	 *
	 * @param unpricedModels models with usage but no configured price: their cost is not included
	 * @param workspaces per-workspace breakdown; empty in a single-workspace report
	 */
	public record UsageReport(LocalDate from, LocalDate to, String currency, UsageTotals totals,
			List<DailyUsage> daily, List<ModelUsage> models, List<UserUsage> users, List<WorkspaceUsage> workspaces,
			List<String> unpricedModels) {
	}

	/**
	 * @param answers answers that called the chat model
	 * @param estimatedTokens whether some token counts were estimated (provider reported none)
	 */
	public record UsageTotals(long answers, long chatInputTokens, long chatOutputTokens, long embeddingTokens,
			BigDecimal estimatedCost, boolean estimatedTokens) {
	}

	public record DailyUsage(LocalDate date, long answers, long chatTokens, long embeddingTokens,
			BigDecimal estimatedCost) {
	}

	public enum UsageKind {

		CHAT, EMBEDDING

	}

	/** @param estimatedCost {@code null} when the model has no configured price */
	public record ModelUsage(String model, UsageKind kind, long inputTokens, long outputTokens,
			BigDecimal estimatedCost) {
	}

	/** @param userId {@code null} for usage of users who no longer exist */
	public record UserUsage(UUID userId, String username, String displayName, long answers, long tokens,
			BigDecimal estimatedCost) {
	}

	/** @param name {@code null} when the workspace has been deleted (its usage still counts) */
	public record WorkspaceUsage(UUID workspaceId, String name, long answers, long tokens, BigDecimal estimatedCost) {
	}

}
