package dev.docquery.api.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.docquery.api.usage.UsageDtos.UsageKind;
import dev.docquery.api.usage.UsageDtos.UsageReport;
import dev.docquery.api.usage.UsageReportRepository.UsageRow;

/** The pure aggregation behind the usage reports. */
class UsageReportBuildTests {

	static final LocalDate MON = LocalDate.of(2026, 11, 9);

	static final UUID WS_A = UUID.randomUUID();

	static final UUID WS_GONE = UUID.randomUUID();

	static final UUID ALICE = UUID.randomUUID();

	static final UUID BOB = UUID.randomUUID();

	static final PriceTable PRICES = PriceTable.parse("chat-model=1/2;embed-model=0.5/0");

	@Test
	void aggregatesByDayModelUserAndWorkspaceAndFillsEmptyDays() {
		List<UsageRow> rows = List.of(
				chat(MON, WS_A, "A", ALICE, "alice", 1_000_000, 500_000, 10),
				chat(MON.plusDays(2), WS_A, "A", BOB, "bob", 2_000_000, 0, 5),
				embedding(MON, WS_GONE, null, ALICE, "alice", 4_000_000));

		UsageReport report = UsageReportService.build(rows, MON, MON.plusDays(3), PRICES, true);

		assertThat(report.totals().answers()).isEqualTo(15);
		assertThat(report.totals().chatInputTokens()).isEqualTo(3_000_000);
		assertThat(report.totals().chatOutputTokens()).isEqualTo(500_000);
		assertThat(report.totals().embeddingTokens()).isEqualTo(4_000_000);
		// (1 + 1) + 2 + 2 = 6 USD
		assertThat(report.totals().estimatedCost()).isEqualByComparingTo("6");
		assertThat(report.daily()).extracting(d -> d.date().toString())
			.containsExactly("2026-11-09", "2026-11-10", "2026-11-11", "2026-11-12");
		assertThat(report.daily().get(0).estimatedCost()).isEqualByComparingTo("4");
		assertThat(report.daily().get(1).answers()).isZero();
		assertThat(report.models()).extracting(m -> m.kind() + " " + m.model())
			.containsExactly("CHAT chat-model", "EMBEDDING embed-model");
		// Most expensive first; a deleted workspace keeps its usage, without a name.
		assertThat(report.users()).extracting(u -> u.username()).containsExactly("alice", "bob");
		assertThat(report.workspaces()).extracting(w -> w.name()).containsExactly("A", null);
		assertThat(report.unpricedModels()).isEmpty();
	}

	@Test
	void unpricedModelsAreListedAndNotCountedAsCost() {
		List<UsageRow> rows = List.of(new UsageRow(MON, WS_A, "A", ALICE, "alice", "Alice", "mystery-model",
				UsageKind.CHAT, 1000, 100, 1, true));

		UsageReport report = UsageReportService.build(rows, MON, MON, PRICES, false);

		assertThat(report.unpricedModels()).containsExactly("mystery-model");
		assertThat(report.models().getFirst().estimatedCost()).isNull();
		assertThat(report.totals().estimatedCost()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(report.totals().estimatedTokens()).isTrue();
		assertThat(report.workspaces()).isEmpty();
	}

	@Test
	void humanizesTheTimeUntilTheQuotaResets() {
		assertThat(QuotaService.humanize(Duration.ofSeconds(30))).isEqualTo("1 min");
		assertThat(QuotaService.humanize(Duration.ofMinutes(59))).isEqualTo("59 min");
		assertThat(QuotaService.humanize(Duration.ofMinutes(120))).isEqualTo("2 h");
		assertThat(QuotaService.humanize(Duration.ofMinutes(195))).isEqualTo("3 h 15 min");
	}

	private static UsageRow chat(LocalDate day, UUID ws, String wsName, UUID user, String username, long in, long out,
			long answers) {
		return new UsageRow(day, ws, wsName, user, username, username, "chat-model", UsageKind.CHAT, in, out, answers,
				false);
	}

	private static UsageRow embedding(LocalDate day, UUID ws, String wsName, UUID user, String username, long in) {
		return new UsageRow(day, ws, wsName, user, username, username, "embed-model", UsageKind.EMBEDDING, in, 0, 1,
				false);
	}

}
