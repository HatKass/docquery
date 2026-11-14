package dev.docquery.api.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.util.StringUtils;
import tools.jackson.databind.json.JsonMapper;

import dev.docquery.api.InfrastructureContainers;
import dev.docquery.api.retrieval.ChunkRetriever;
import dev.docquery.api.support.ApiClient;
import dev.docquery.api.support.TestUser;

/**
 * The RAG evaluation with <b>real models</b>, configured exactly like the application (application.yml,
 * the repo's .env and environment variables): Ollama by default, or a cloud provider. Not part of the
 * normal build (tag {@value #TAG}); run it with the {@code rag-evaluation} Maven profile:
 *
 * <pre>
 * OLLAMA_CHAT_MODEL=qwen2.5:7b ./mvnw test -Prag-evaluation
 * CHAT_PROVIDER=openai OPENAI_API_KEY=... ./mvnw test -Prag-evaluation
 * </pre>
 *
 * Postgres, Kafka and storage are Testcontainers as in every integration test; nothing touches the
 * local stack's data. Results go to {@code /evaluation/results/<label>.json|md} and the comparison
 * table {@code /evaluation/results/README.md} is rebuilt from all results there. The test fails only
 * when requests fail (e.g. the model is not reachable); quality is reported, not asserted.
 */
@Tag(LiveRagEvaluationTests.TAG)
@Import(InfrastructureContainers.class)
@SpringBootTest(properties = {
		// Every question of the set, by one user, in one day: no quotas during an evaluation.
		"docquery.usage.quotas.daily-questions-per-user=0", "docquery.usage.quotas.daily-uploads-per-user=0",
		// Local models can be slow to load (qwen2.5:7b on a 4 GB GPU: ~2.5 min cold).
		"spring.http.clients.read-timeout=600s", "docquery.chat.stream-timeout=15m" })
@AutoConfigureMockMvc
class LiveRagEvaluationTests {

	static final String TAG = "live-evaluation";

	static final Path RESULTS = EvaluationSet.REPO.resolve("evaluation").resolve("results");

	private static final Logger log = LoggerFactory.getLogger(LiveRagEvaluationTests.class);

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JsonMapper json;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	EmbeddingModel embeddingModel;

	@Autowired
	ChunkRetriever retriever;

	@Value("${docquery.chat.model}")
	String chatModel;

	@Value("${docquery.retrieval.embedding-model}")
	String embeddingModelName;

	/** The worker's prefix for chunks (nomic-embed-text); same variable and default as the worker. */
	@Value("${EMBEDDING_DOCUMENT_PREFIX:search_document: }")
	String documentPrefix;

	/** Name of the run in the results; defaults to the chat model. */
	@Value("${EVAL_LABEL:}")
	String label;

	@Test
	void evaluateWithTheConfiguredModels() {
		String runLabel = StringUtils.hasText(this.label) ? this.label : this.chatModel;
		log.info("Live RAG evaluation '{}': chat model {}, embedding model {}", runLabel, this.chatModel,
				this.embeddingModelName);
		ApiClient api = new ApiClient(this.mvc, this.json);
		TestUser owner = TestUser.random("live-evaluation");
		api.signIn(owner);
		UUID workspaceId = api.createWorkspace(owner, "Live RAG evaluation");
		new EvaluationCorpus(api, this.jdbc, this.json, this.embeddingModel, this.embeddingModelName,
				this.documentPrefix)
			.load(owner, workspaceId);

		EvaluationResult result = new RagEvaluator(api, this.retriever, this.jdbc).run(
				EvaluationSet.load(EvaluationSet.DEFAULT_LOCATION), owner, workspaceId, runLabel, this.chatModel,
				this.embeddingModelName, log::info);

		Path report = EvaluationReports.write(result, RESULTS, EvaluationReports.fileName(runLabel));
		Path comparison = EvaluationReports.writeComparison(RESULTS);
		log.info("Report: {}  Comparison: {}", report, comparison);
		assertThat(result.summary().answers().failedRequests()).as("answers that failed (is the model reachable?)")
			.isZero();
	}

}
