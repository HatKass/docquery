package dev.docquery.api.usage;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.docquery.api.common.error.ApiException;
import dev.docquery.api.usage.UsageDtos.DailyUsage;
import dev.docquery.api.usage.UsageDtos.ModelUsage;
import dev.docquery.api.usage.UsageDtos.UsageKind;
import dev.docquery.api.usage.UsageDtos.UsageReport;
import dev.docquery.api.usage.UsageDtos.UsageTotals;
import dev.docquery.api.usage.UsageDtos.UserUsage;
import dev.docquery.api.usage.UsageDtos.WorkspaceUsage;
import dev.docquery.api.usage.UsageReportRepository.UsageRow;
import dev.docquery.api.user.CurrentUser;
import dev.docquery.api.workspace.WorkspaceAccess;
import dev.docquery.api.workspace.WorkspaceRole;

/** Token and cost reports: per workspace (its owners) and for the whole platform (admins). */
@Service
public class UsageReportService {

	static final int MAX_DAYS = 366;

	private final UsageReportRepository repository;

	private final WorkspaceAccess access;

	private final PriceTable prices;

	private final Clock clock = Clock.systemUTC();

	UsageReportService(UsageReportRepository repository, WorkspaceAccess access, PriceTable prices) {
		this.repository = repository;
		this.access = access;
		this.prices = prices;
	}

	/** One workspace's usage, by day, model and member. Owners only: it shows what each member used. */
	@Transactional(readOnly = true)
	public UsageReport workspaceReport(UUID workspaceId, int days, CurrentUser user) {
		this.access.require(workspaceId, user, WorkspaceRole.OWNER);
		LocalDate from = from(days);
		return build(this.repository.find(from, workspaceId), from, today(), this.prices, false);
	}

	/** All workspaces, including deleted ones. Callers must have checked the admin role. */
	@Transactional(readOnly = true)
	public UsageReport platformReport(int days) {
		LocalDate from = from(days);
		return build(this.repository.find(from, null), from, today(), this.prices, true);
	}

	private LocalDate today() {
		return LocalDate.now(this.clock.withZone(ZoneOffset.UTC));
	}

	private LocalDate from(int days) {
		if (days < 1 || days > MAX_DAYS) {
			throw ApiException.unprocessable("invalid-period", "days must be between 1 and " + MAX_DAYS + ".");
		}
		return today().minusDays(days - 1L);
	}

	/** Pure aggregation, unit-tested without a database. */
	static UsageReport build(List<UsageRow> rows, LocalDate from, LocalDate to, PriceTable prices,
			boolean byWorkspace) {
		Map<LocalDate, Acc> daily = new LinkedHashMap<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			daily.put(d, new Acc());
		}
		Map<String, long[]> models = new LinkedHashMap<>();
		Map<UUID, Acc> users = new LinkedHashMap<>();
		Map<UUID, String[]> userNames = new LinkedHashMap<>();
		Map<UUID, Acc> workspaces = new LinkedHashMap<>();
		Map<UUID, String> workspaceNames = new LinkedHashMap<>();
		Acc total = new Acc();
		long chatIn = 0;
		long chatOut = 0;
		long embedding = 0;
		boolean estimated = false;
		TreeSet<String> unpriced = new TreeSet<>();

		for (UsageRow row : rows) {
			Optional<BigDecimal> cost = prices.cost(row.model(), row.inputTokens(), row.outputTokens());
			if (cost.isEmpty()) {
				unpriced.add(row.model());
			}
			BigDecimal value = cost.orElse(BigDecimal.ZERO);
			long answers = (row.kind() == UsageKind.CHAT) ? row.events() : 0;
			long tokens = row.inputTokens() + row.outputTokens();
			if (row.kind() == UsageKind.CHAT) {
				chatIn += row.inputTokens();
				chatOut += row.outputTokens();
			}
			else {
				embedding += row.inputTokens();
			}
			estimated |= row.estimated();
			total.add(answers, tokens, row.kind(), value);
			daily.computeIfAbsent(row.day(), d -> new Acc()).add(answers, tokens, row.kind(), value);
			long[] model = models.computeIfAbsent(row.kind() + "\u0000" + row.model(), k -> new long[2]);
			model[0] += row.inputTokens();
			model[1] += row.outputTokens();
			users.computeIfAbsent(row.userId(), id -> new Acc()).add(answers, tokens, row.kind(), value);
			userNames.putIfAbsent(row.userId(), new String[] { row.username(), row.displayName() });
			if (byWorkspace) {
				workspaces.computeIfAbsent(row.workspaceId(), id -> new Acc()).add(answers, tokens, row.kind(), value);
				workspaceNames.putIfAbsent(row.workspaceId(), row.workspaceName());
			}
		}

		List<DailyUsage> days = daily.entrySet()
			.stream()
			.map(e -> new DailyUsage(e.getKey(), e.getValue().answers, e.getValue().chatTokens,
					e.getValue().embeddingTokens, e.getValue().cost))
			.toList();
		List<ModelUsage> modelList = new ArrayList<>();
		models.forEach((key, tokens) -> {
			int sep = key.indexOf('\u0000');
			String model = key.substring(sep + 1);
			modelList.add(new ModelUsage(model, UsageKind.valueOf(key.substring(0, sep)), tokens[0], tokens[1],
					prices.cost(model, tokens[0], tokens[1]).orElse(null)));
		});
		modelList.sort(Comparator.comparing(ModelUsage::kind).thenComparing(ModelUsage::model));
		List<UserUsage> userList = users.entrySet()
			.stream()
			.map(e -> new UserUsage(e.getKey(), userNames.get(e.getKey())[0], userNames.get(e.getKey())[1],
					e.getValue().answers, e.getValue().tokens(), e.getValue().cost))
			.sorted(byCostThenTokens(UserUsage::estimatedCost, UserUsage::tokens))
			.toList();
		List<WorkspaceUsage> workspaceList = workspaces.entrySet()
			.stream()
			.map(e -> new WorkspaceUsage(e.getKey(), workspaceNames.get(e.getKey()), e.getValue().answers,
					e.getValue().tokens(), e.getValue().cost))
			.sorted(byCostThenTokens(WorkspaceUsage::estimatedCost, WorkspaceUsage::tokens))
			.toList();
		return new UsageReport(from, to, "USD",
				new UsageTotals(total.answers, chatIn, chatOut, embedding, total.cost, estimated), days, modelList,
				userList, workspaceList, List.copyOf(unpriced));
	}

	private static <T> Comparator<T> byCostThenTokens(Function<T, BigDecimal> cost, Function<T, Long> tokens) {
		return Comparator.comparing(cost, Comparator.nullsLast(Comparator.reverseOrder()))
			.thenComparing(tokens, Comparator.reverseOrder());
	}

	private static final class Acc {

		long answers;

		long chatTokens;

		long embeddingTokens;

		BigDecimal cost = BigDecimal.ZERO;

		void add(long answers, long tokens, UsageKind kind, BigDecimal cost) {
			this.answers += answers;
			if (kind == UsageKind.CHAT) {
				this.chatTokens += tokens;
			}
			else {
				this.embeddingTokens += tokens;
			}
			this.cost = this.cost.add(Objects.requireNonNull(cost));
		}

		long tokens() {
			return this.chatTokens + this.embeddingTokens;
		}

	}

}
