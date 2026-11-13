package dev.docquery.api.usage;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.docquery.api.usage.UsageDtos.MyQuotas;
import dev.docquery.api.usage.UsageDtos.UsageReport;
import dev.docquery.api.user.CurrentUser;

@RestController
class UsageController {

	private final QuotaService quotas;

	private final UsageReportService reports;

	UsageController(QuotaService quotas, UsageReportService reports) {
		this.quotas = quotas;
		this.reports = reports;
	}

	/** The caller's quotas and how much of them is left today. */
	@GetMapping("/api/me/quotas")
	MyQuotas myQuotas(CurrentUser user) {
		return this.quotas.status(user.id());
	}

	/** Tokens and estimated cost of one workspace over the last {@code days} days (owners only). */
	@GetMapping("/api/workspaces/{workspaceId}/usage")
	UsageReport workspaceUsage(@PathVariable UUID workspaceId, @RequestParam(defaultValue = "30") int days,
			CurrentUser user) {
		return this.reports.workspaceReport(workspaceId, days, user);
	}

}
