package dev.docquery.api.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.ChunkFixtures;
import dev.docquery.api.support.SampleFiles;
import dev.docquery.api.support.StubEmbeddingModel;
import dev.docquery.api.support.TestUser;

/** Hybrid retrieval against real pgvector + full-text search, through the search endpoint. */
class HybridSearchTests extends ApiIntegrationTest {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	StubEmbeddingModel embeddings;

	ChunkFixtures chunks;

	TestUser owner;

	UUID workspaceId;

	UUID handbookId;

	@BeforeEach
	void handbook() {
		this.embeddings.reset();
		this.chunks = new ChunkFixtures(this.jdbc);
		this.owner = TestUser.random("search-owner");
		this.api.signIn(this.owner);
		this.workspaceId = this.api.createWorkspace(this.owner, "Search");
		this.handbookId = this.api.uploadDocument(this.owner, this.workspaceId, "Handbook.pdf", SampleFiles.pdf());
		this.chunks.index(this.workspaceId, this.handbookId, 4, "Employees receive 24 days of paid vacation each year.",
				"Expense claims must be filed within 30 days and include a receipt.",
				"The office router model is XR-4471 and is reset every Monday.");
	}

	@Test
	void findsTheRelevantChunkWithPageAndHowItWasFound() {
		JsonNode hits = search("How many days of paid vacation do employees receive?");

		JsonNode best = hits.get(0);
		assertThat(best.get("documentId").asString()).isEqualTo(this.handbookId.toString());
		assertThat(best.get("documentName").asString()).isEqualTo("Handbook.pdf");
		assertThat(best.get("page").asInt()).isEqualTo(4);
		assertThat(best.get("snippet").asString()).contains("24 days of paid vacation");
		// Found by both halves of the hybrid search.
		assertThat(best.get("vectorRank").asInt()).isEqualTo(1);
		assertThat(best.get("keywordRank").asInt()).isEqualTo(1);
	}

	@Test
	void fullTextSearchStemsWords() {
		// "claim" / "filing" vs "claims" / "filed" in the text: the 'english' configuration matches stems.
		JsonNode hits = search("filing a claim");

		assertThat(hits.get(0).get("snippet").asString()).startsWith("Expense claims");
		assertThat(hits.get(0).get("keywordRank").asInt()).isEqualTo(1);
	}

	@Test
	void stillFindsExactTermsWhenTheEmbeddingProviderIsDown() {
		this.embeddings.failNext(1);

		JsonNode hits = search("XR-4471");

		assertThat(hits.get(0).get("snippet").asString()).contains("XR-4471");
		assertThat(hits.get(0).get("vectorRank").isNull()).isTrue();
	}

	@Test
	void unrelatedQuestionFindsNothing() {
		assertThat(search("pizza dough recipe").size()).isZero();
	}

	@Test
	void ignoresChunksEmbeddedWithAnotherModelForVectorSearch() {
		this.jdbc.sql("UPDATE document_chunk SET embedding_model = 'old-model' WHERE document_id = :id")
			.param("id", this.handbookId)
			.update();

		JsonNode hits = search("How many days of paid vacation do employees receive?");

		// Still found by full-text search, but no longer by vector similarity.
		assertThat(hits.get(0).get("snippet").asString()).contains("24 days");
		assertThat(hits.get(0).get("vectorRank").isNull()).isTrue();
	}

	@Test
	void neverReturnsChunksOfAnotherWorkspace() {
		TestUser other = TestUser.random("search-other");
		this.api.signIn(other);
		UUID otherWorkspace = this.api.createWorkspace(other, "Other");
		UUID secret = this.api.uploadDocument(other, otherWorkspace, "Secret.pdf", SampleFiles.pdf());
		// A perfect match for the query, in the other tenant.
		this.chunks.index(otherWorkspace, secret, 1, "secret merger codename bluebird");

		assertThat(search("secret merger codename bluebird").size()).isZero();
		assertThat(searchAs(other, otherWorkspace, "secret merger codename bluebird"))
			.bodyJson()
			.extractingPath("$[0].documentName")
			.isEqualTo("Secret.pdf");
	}

	@Test
	void rejectsBlankQueries() {
		assertThat(searchAs(this.owner, this.workspaceId, " ")).hasStatus(HttpStatus.BAD_REQUEST);
	}

	private JsonNode search(String query) {
		MvcTestResult result = searchAs(this.owner, this.workspaceId, query);
		assertThat(result).hasStatusOk();
		return this.api.body(result);
	}

	private MvcTestResult searchAs(TestUser user, UUID workspaceId, String query) {
		return this.mvc.get()
			.uri("/api/workspaces/{workspaceId}/search", workspaceId)
			.param("q", query)
			.with(user.auth())
			.exchange();
	}

}
