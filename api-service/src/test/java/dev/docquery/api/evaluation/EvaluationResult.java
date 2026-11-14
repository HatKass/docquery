package dev.docquery.api.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The outcome of one evaluation run: a summary (what the comparison table shows) and every case.
 *
 * @param label short name of the run, e.g. the chat model
 */
record EvaluationResult(String label, String chatModel, String embeddingModel, String startedAt,
		Summary summary, List<CaseResult> cases) {

	/**
	 * @param hitAt1 share of answerable single-turn cases whose first search hit is an expected page
	 * @param hitAt5 the same within the first 5 hits
	 * @param mrr mean reciprocal rank of the first expected page within 10 hits
	 * @param contextRecall mean share of expected pages among the sources the model is given
	 */
	record Retrieval(int cases, double hitAt1, double hitAt5, double mrr, double contextRecall) {
	}

	/**
	 * @param passRate share of cases whose answer passed every check
	 * @param falseNotFound answerable cases declined although the documents answer them
	 * @param refusalsCorrect unanswerable (and must-decline attribution) cases that were declined
	 * @param attributionPassed attribution cases that passed
	 * @param padded answers over their word limit
	 * @param citationsCorrect answerable cases citing only (and at least one of) the expected documents
	 * @param injectionResisted injection cases whose answer contains none of the injected text
	 */
	record Answers(int cases, double passRate, Map<String, String> byCategory, int falseNotFound,
			String refusalsCorrect, String attributionPassed, int padded, String citationsCorrect,
			String injectionResisted, int failedRequests) {
	}

	record Latency(long p50Millis, long p95Millis, long maxMillis) {
	}

	record Tokens(double meanPrompt, double meanCompletion) {
	}

	record Summary(Retrieval retrieval, Answers answers, Latency latency, Tokens tokens) {
	}

	/**
	 * @param firstHitRank 1-based rank of the first expected page among the search hits, {@code null} if
	 * not found in the top 10 or not measured
	 * @param contextRecall share of expected pages given to the model, {@code null} if not measured
	 * @param citations "document p. n" for each citation
	 */
	record CaseResult(String id, String category, String question, Integer firstHitRank, Double contextRecall,
			boolean passed, List<String> failures, String answer, List<String> citations, boolean requestFailed,
			long latencyMillis, Integer promptTokens, Integer completionTokens, boolean refusal,
			boolean falseNotFound, boolean withinLength, boolean citationsCorrect, boolean excludesForbidden,
			boolean answerable) {
	}

	static Summary summarize(List<CaseResult> cases) {
		List<CaseResult> ranked = cases.stream().filter(c -> c.contextRecall() != null).toList();
		Retrieval retrieval = new Retrieval(ranked.size(), share(ranked, c -> rank(c) == 1),
				share(ranked, c -> rank(c) <= 5),
				ranked.stream().mapToDouble(c -> (c.firstHitRank() == null) ? 0 : 1.0 / c.firstHitRank()).average().orElse(0),
				ranked.stream().mapToDouble(CaseResult::contextRecall).average().orElse(0));

		Map<String, String> byCategory = new LinkedHashMap<>();
		cases.stream().map(CaseResult::category).distinct().forEach(category -> {
			List<CaseResult> inCategory = cases.stream().filter(c -> c.category().equals(category)).toList();
			byCategory.put(category, ratio(inCategory, CaseResult::passed));
		});
		List<CaseResult> mustDecline = cases.stream().filter(c -> !c.answerable()).toList();
		List<CaseResult> answerable = cases.stream().filter(CaseResult::answerable).toList();
		Answers answers = new Answers(cases.size(), share(cases, CaseResult::passed), byCategory,
				(int) cases.stream().filter(CaseResult::falseNotFound).count(),
				ratio(mustDecline, CaseResult::refusal),
				ratio(cases.stream().filter(c -> c.category().equals("attribution")).toList(), CaseResult::passed),
				(int) cases.stream().filter(c -> !c.withinLength()).count(),
				ratio(answerable, CaseResult::citationsCorrect),
				ratio(cases.stream().filter(c -> c.category().equals("injection")).toList(),
						CaseResult::excludesForbidden),
				(int) cases.stream().filter(CaseResult::requestFailed).count());

		List<Long> latencies = cases.stream().map(CaseResult::latencyMillis).sorted().toList();
		Latency latency = new Latency(percentile(latencies, 50), percentile(latencies, 95),
				latencies.isEmpty() ? 0 : latencies.getLast());
		Tokens tokens = new Tokens(
				cases.stream().filter(c -> c.promptTokens() != null).mapToInt(CaseResult::promptTokens).average().orElse(0),
				cases.stream()
					.filter(c -> c.completionTokens() != null)
					.mapToInt(CaseResult::completionTokens)
					.average()
					.orElse(0));
		return new Summary(retrieval, answers, latency, tokens);
	}

	private static int rank(CaseResult c) {
		return (c.firstHitRank() != null) ? c.firstHitRank() : Integer.MAX_VALUE;
	}

	private static double share(List<CaseResult> cases, Predicate<CaseResult> test) {
		return cases.isEmpty() ? 0 : (double) cases.stream().filter(test).count() / cases.size();
	}

	private static String ratio(List<CaseResult> cases, Predicate<CaseResult> test) {
		return cases.stream().filter(test).count() + "/" + cases.size();
	}

	static long percentile(List<Long> sorted, int percentile) {
		if (sorted.isEmpty()) {
			return 0;
		}
		int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
		return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
	}

	/** Failed cases first, then by id: what a reader of the report wants to see first. */
	List<CaseResult> casesForReport() {
		List<CaseResult> sorted = new ArrayList<>(this.cases);
		sorted.sort(Comparator.comparing(CaseResult::passed).thenComparing(CaseResult::category));
		return sorted;
	}

}
