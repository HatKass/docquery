package dev.docquery.api.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Quotas and prices ({@code docquery.usage.*}); defaults in application.yml.
 *
 * @param quotas per-user and per-workspace limits that keep a public demo affordable
 * @param prices model prices for cost estimates, see {@link PriceTable#parse}
 */
@ConfigurationProperties("docquery.usage")
public record UsageProperties(@DefaultValue Quotas quotas, @DefaultValue("") String prices) {

	/**
	 * Every limit: {@code 0} means "no limit".
	 *
	 * @param maxWorkspacesPerUser workspaces a user may create (and that still exist); a platform admin
	 * can override it per user
	 * @param dailyQuestionsPerUser questions a user may ask per UTC day, across all workspaces
	 * @param dailyUploadsPerUser documents a user may upload per UTC day, across all workspaces
	 * @param maxDocumentsPerWorkspace documents one workspace may hold
	 */
	public record Quotas(@DefaultValue("3") int maxWorkspacesPerUser, @DefaultValue("50") int dailyQuestionsPerUser,
			@DefaultValue("20") int dailyUploadsPerUser, @DefaultValue("100") int maxDocumentsPerWorkspace) {

		public Quotas {
			if (maxWorkspacesPerUser < 0 || dailyQuestionsPerUser < 0 || dailyUploadsPerUser < 0
					|| maxDocumentsPerWorkspace < 0) {
				throw new IllegalArgumentException("docquery.usage.quotas.* must be >= 0 (0 = no limit)");
			}
		}

	}

}
