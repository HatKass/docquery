package dev.docquery.api.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.docquery.api.evaluation.EvaluationResult.Summary;
import dev.docquery.api.retrieval.ChunkRetriever;
import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.StubChatModel;
import dev.docquery.api.support.StubEmbeddingModel;
import dev.docquery.api.support.TestUser;

/**
 * The RAG evaluation as it runs in CI: the full evaluation set over the sample documents, with the
 * offline models (bag-of-words {@link StubEmbeddingModel}, {@link ExtractiveStubAnswers} as the chat
 * model). Fast, free and deterministic, so it guards retrieval and the answer pipeline against
 * regressions. The thresholds are the baseline measured when the set was written, minus a small margin;
 * raise them when retrieval improves.
 * <p>
 * Report: {@code target/evaluation/ci-stub.md} (uploaded by CI). Real models: {@link LiveRagEvaluationTests}.
 */
class RagEvaluationTests extends ApiIntegrationTest {

	private static final Logger log = LoggerFactory.getLogger(RagEvaluationTests.class);

	static final Path REPORT_DIRECTORY = Path.of("target", "evaluation");

	@Autowired
	StubChatModel chatModel;

	@Autowired
	StubEmbeddingModel embeddingModel;

	@Autowired
	ChunkRetriever retriever;

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void resetModels() {
		this.chatModel.reset();
	}

	@Test
	void evaluationSetMeetsTheBaseline() {
		EvaluationSet set = EvaluationSet.load(EvaluationSet.DEFAULT_LOCATION);
		this.chatModel.reply(ExtractiveStubAnswers::reply);
		TestUser owner = TestUser.random("evaluation");
		this.api.signIn(owner);
		UUID workspaceId = this.api.createWorkspace(owner, "RAG evaluation");
		Map<String, UUID> documents = new EvaluationCorpus(this.api, this.jdbc, this.json, this.embeddingModel,
				StubEmbeddingModel.MODEL, "")
			.load(owner, workspaceId);
		assertThat(documents).hasSizeGreaterThanOrEqualTo(5);

		EvaluationResult result = new RagEvaluator(this.api, this.retriever, this.jdbc).run(set, owner, workspaceId,
				"ci-stub", StubChatModel.MODEL, StubEmbeddingModel.MODEL, log::info);
		Path report = EvaluationReports.write(result, REPORT_DIRECTORY, "ci-stub");
		log.info("RAG evaluation report: {}", report.toAbsolutePath());

		Summary summary = result.summary();
		assertThat(summary.answers().failedRequests()).as("failed requests").isZero();
		assertThat(summary.retrieval().cases()).isGreaterThanOrEqualTo(20);
		// Baseline with stub embeddings (lexical) + full-text search; see the report for each case.
		assertThat(summary.retrieval().hitAt5()).as("retrieval hit@5").isGreaterThanOrEqualTo(Baseline.HIT_AT_5);
		assertThat(summary.retrieval().mrr()).as("retrieval MRR@10").isGreaterThanOrEqualTo(Baseline.MRR);
		assertThat(summary.retrieval().contextRecall()).as("context recall")
			.isGreaterThanOrEqualTo(Baseline.CONTEXT_RECALL);
		assertThat(summary.answers().passRate()).as("answers passing").isGreaterThanOrEqualTo(Baseline.ANSWER_PASS_RATE);
	}

	/**
	 * Measured on the stub models when the evaluation set was written (2026-11, 32 cases): hit@5 100 %,
	 * MRR 0.87, context recall 100 %, 28 % of answers passing (the extractive stub cannot decline,
	 * attribute or assess like a model; real models: evaluation/results). Lowered slightly so that one
	 * changed case does not fail the build; a real regression does.
	 */
	static final class Baseline {

		static final double HIT_AT_5 = 0.95;

		static final double MRR = 0.80;

		static final double CONTEXT_RECALL = 0.95;

		static final double ANSWER_PASS_RATE = 0.25;

		private Baseline() {
		}

	}

}
