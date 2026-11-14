package dev.docquery.api.evaluation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import dev.docquery.api.chat.ChatTestAccess;
import dev.docquery.api.evaluation.AnswerScorer.Citation;
import dev.docquery.api.evaluation.AnswerScorer.Score;
import dev.docquery.api.evaluation.EvaluationResult.CaseResult;
import dev.docquery.api.evaluation.EvaluationSet.Case;
import dev.docquery.api.evaluation.EvaluationSet.ExpectedSource;
import dev.docquery.api.retrieval.ChunkRetriever;
import dev.docquery.api.retrieval.RetrievedChunk;
import dev.docquery.api.support.ApiClient;
import dev.docquery.api.support.SseEvents;
import dev.docquery.api.support.TestUser;

/**
 * Runs the evaluation set against a workspace holding the evaluation documents:
 * <ol>
 * <li><b>retrieval</b>: the search the chat runs (hybrid, the same {@link ChunkRetriever}), scored by
 * where the expected pages rank, and which of them the model would be given;</li>
 * <li><b>answers</b>: each question is asked through the real chat endpoint (SSE stream, grounding
 * prompt, citation extraction) in a fresh conversation, and the stored answer is scored by
 * {@link AnswerScorer}.</li>
 * </ol>
 * The chat and embedding models are whatever the application context provides: stubs in CI, real
 * models in a live evaluation.
 */
final class RagEvaluator {

	static final int SEARCH_DEPTH = 10;

	private final ApiClient api;

	private final ChunkRetriever retriever;

	private final JdbcClient jdbc;

	RagEvaluator(ApiClient api, ChunkRetriever retriever, JdbcClient jdbc) {
		this.api = api;
		this.retriever = retriever;
		this.jdbc = jdbc;
	}

	EvaluationResult run(EvaluationSet set, TestUser owner, UUID workspaceId, String label, String chatModel,
			String embeddingModel, Consumer<String> progress) {
		String startedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
		List<CaseResult> results = new ArrayList<>();
		int n = 0;
		for (Case testCase : set.cases()) {
			CaseResult result = evaluate(testCase, owner, workspaceId);
			results.add(result);
			progress.accept(String.format("[%d/%d] %-26s %s %6d ms %s", ++n, set.cases().size(), testCase.id(),
					result.passed() ? "PASS" : "FAIL", result.latencyMillis(),
					result.passed() ? "" : String.join("; ", result.failures())));
		}
		return new EvaluationResult(label, chatModel, embeddingModel, startedAt, EvaluationResult.summarize(results),
				List.copyOf(results));
	}

	private CaseResult evaluate(Case testCase, TestUser owner, UUID workspaceId) {
		Integer firstHitRank = null;
		Double contextRecall = null;
		// Ranking is only meaningful for self-contained questions: a follow-up is searched via its rewrite.
		if (testCase.answerable() && testCase.conversation().isEmpty()) {
			List<RetrievedChunk> hits = this.retriever.retrieve(workspaceId, testCase.question(), SEARCH_DEPTH);
			firstHitRank = firstRelevantRank(testCase.sources(), hits);
			List<RetrievedChunk> context = this.retriever.retrieveForAnswer(workspaceId,
					List.of(testCase.question()), ChatTestAccess.isEvaluative(testCase.question()));
			contextRecall = recall(testCase.sources(), context);
		}

		UUID conversationId = this.api.createConversation(owner, workspaceId);
		for (String earlier : testCase.conversation()) {
			this.api.askAndRead(owner, workspaceId, conversationId, earlier);
		}
		long started = System.nanoTime();
		List<SseEvents.Event> events = this.api.askAndRead(owner, workspaceId, conversationId, testCase.question());
		long latencyMillis = (System.nanoTime() - started) / 1_000_000;

		SseEvents.Event last = SseEvents.last(events);
		boolean requestFailed = !"done".equals(last.name());
		JsonNode answer = last.data().get("answer");
		String content = (answer != null && !answer.isNull()) ? answer.get("content").asString()
				: Optional.ofNullable(last.data().get("detail")).map(JsonNode::asString).orElse("");
		List<Citation> citations = new ArrayList<>();
		if (answer != null && answer.get("citations") != null) {
			for (JsonNode citation : answer.get("citations")) {
				JsonNode page = citation.get("pageNumber");
				citations.add(new Citation(citation.get("documentName").asString(),
						(page == null || page.isNull()) ? null : page.asInt()));
			}
		}
		Score score = AnswerScorer.score(testCase, content, citations);
		List<String> failures = new ArrayList<>(score.failures());
		if (requestFailed) {
			failures.addFirst("the answer failed: " + content);
		}
		Integer[] tokens = (answer != null && !answer.isNull()) ? tokens(UUID.fromString(answer.get("id").asString()))
				: new Integer[] { null, null };
		return new CaseResult(testCase.id(), testCase.category(), testCase.question(), firstHitRank, contextRecall,
				failures.isEmpty(), List.copyOf(failures), content,
				citations.stream().map(c -> c.documentName() + ((c.page() != null) ? " p. " + c.page() : "")).toList(),
				requestFailed, latencyMillis, tokens[0], tokens[1], score.refusal(), score.falseNotFound(),
				score.withinLength(), score.citationsCorrect(), score.excludesForbidden(), testCase.answerable());
	}

	static Integer firstRelevantRank(List<ExpectedSource> expected, List<RetrievedChunk> hits) {
		for (int i = 0; i < hits.size(); i++) {
			RetrievedChunk hit = hits.get(i);
			if (expected.stream().anyMatch(e -> e.matches(hit.documentName(), hit.pageNumber()))) {
				return i + 1;
			}
		}
		return null;
	}

	/** Share of the expected (document, page) items present in the context. */
	static double recall(List<ExpectedSource> expected, List<RetrievedChunk> context) {
		int items = 0;
		int found = 0;
		for (ExpectedSource source : expected) {
			if (source.pages().isEmpty()) {
				items++;
				found += context.stream().anyMatch(c -> c.documentName().equals(source.document())) ? 1 : 0;
			}
			else {
				for (Integer page : source.pages()) {
					items++;
					found += context.stream()
						.anyMatch(c -> c.documentName().equals(source.document()) && page.equals(c.pageNumber())) ? 1
								: 0;
				}
			}
		}
		return (items == 0) ? 1 : (double) found / items;
	}

	/** Prompt and completion tokens the answer was charged for (the usage ledger). */
	private Integer[] tokens(UUID answerId) {
		return this.jdbc.sql("SELECT prompt_tokens, completion_tokens FROM chat_usage WHERE message_id = :id")
			.param("id", answerId)
			.query((rs, row) -> new Integer[] { rs.getInt(1), rs.getInt(2) })
			.optional()
			.orElse(new Integer[] { null, null });
	}

}
