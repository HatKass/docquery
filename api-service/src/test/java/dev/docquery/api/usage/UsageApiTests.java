package dev.docquery.api.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.ChunkFixtures;
import dev.docquery.api.support.SampleFiles;
import dev.docquery.api.support.StubChatModel;
import dev.docquery.api.support.StubEmbeddingModel;
import dev.docquery.api.support.TestUser;

/**
 * Token and cost tracking per workspace and per user. The stub chat model reports 100 prompt and 10
 * completion tokens per answer; the test prices are 1 / 2 USD per million tokens (chat) and 0.5
 * (embeddings), see TestcontainersConfiguration.
 */
class UsageApiTests extends ApiIntegrationTest {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	StubChatModel model;

	TestUser owner;

	TestUser member;

	UUID ownerId;

	UUID memberId;

	UUID workspaceId;

	@BeforeEach
	void setUp() {
		this.model.reset();
		this.owner = TestUser.random("usage-owner");
		this.member = TestUser.random("usage-member");
		this.ownerId = this.api.signIn(this.owner);
		this.memberId = this.api.signIn(this.member);
		this.workspaceId = this.api.createWorkspace(this.owner, "Usage");
		this.api.addMember(this.owner, this.workspaceId, this.member, "MEMBER");
		UUID documentId = this.api.uploadDocument(this.owner, this.workspaceId, "Handbook.pdf", SampleFiles.pdf());
		new ChunkFixtures(this.jdbc).index(this.workspaceId, documentId, 1, "Employees receive 24 days of paid vacation.");
		// What the ingestion worker records when it embeds a document.
		this.jdbc.sql("""
				INSERT INTO embedding_usage (workspace_id, user_id, document_id, model, input_tokens, estimated, request_count)
				VALUES (:ws, :u, :doc, :model, 2000, false, 1)
				""")
			.param("ws", this.workspaceId)
			.param("u", this.ownerId)
			.param("doc", documentId)
			.param("model", StubEmbeddingModel.MODEL)
			.update();
	}

	@Test
	void ownersSeeTokensAndCostPerMemberAndModel() {
		ask(this.owner);
		ask(this.member);
		ask(this.member);

		assertThat(this.api.get(this.owner, usageUri())).hasStatusOk().bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.currency").isEqualTo("USD");
			json.assertThat().extractingPath("$.totals.answers").isEqualTo(3);
			json.assertThat().extractingPath("$.totals.chatInputTokens").isEqualTo(300);
			json.assertThat().extractingPath("$.totals.chatOutputTokens").isEqualTo(30);
			json.assertThat().extractingPath("$.totals.embeddingTokens").isEqualTo(2000);
			// 3 x (100 x 1 + 10 x 2) / 1M + 2000 x 0.5 / 1M = 0.00036 + 0.001
			json.assertThat().extractingPath("$.totals.estimatedCost").asNumber().satisfies(
					cost -> assertThat(cost.doubleValue()).isEqualTo(0.00136, org.assertj.core.data.Offset.offset(1e-9)));
			json.assertThat().extractingPath("$.daily.length()").isEqualTo(30);
			json.assertThat().extractingPath("$.daily[29].answers").isEqualTo(3);
			json.assertThat().extractingPath("$.models[*].model").asArray()
				.containsExactly(StubChatModel.MODEL, StubEmbeddingModel.MODEL);
			// The member asked more questions but the owner's embedding cost puts them first.
			json.assertThat().extractingPath("$.users[*].userId").asArray()
				.containsExactly(this.ownerId.toString(), this.memberId.toString());
			json.assertThat().extractingPath("$.users[1].answers").isEqualTo(2);
			json.assertThat().extractingPath("$.workspaces").asArray().isEmpty();
			json.assertThat().extractingPath("$.unpricedModels").asArray().isEmpty();
		});
	}

	@Test
	void membersCannotSeeWorkspaceUsage() {
		assertThat(this.api.get(this.member, usageUri())).hasStatus(HttpStatus.FORBIDDEN)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:insufficient-role");
	}

	@Test
	void usageSurvivesDeletingTheConversation() {
		UUID conversationId = ask(this.member);

		this.api.delete(this.member, "/api/workspaces/" + this.workspaceId + "/conversations/" + conversationId);

		assertThat(this.api.get(this.owner, usageUri())).bodyJson().extractingPath("$.totals.answers").isEqualTo(1);
	}

	/** Spent tokens stay on the platform's books after the workspace is gone. */
	@Test
	void usageSurvivesDeletingTheWorkspace() {
		ask(this.owner);

		this.api.delete(this.owner, "/api/workspaces/" + this.workspaceId);

		assertThat(this.jdbc.sql("SELECT count(*) FROM chat_usage WHERE workspace_id = :ws")
			.param("ws", this.workspaceId)
			.query(Integer.class)
			.single()).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT count(*) FROM embedding_usage WHERE workspace_id = :ws")
			.param("ws", this.workspaceId)
			.query(Integer.class)
			.single()).isEqualTo(1);
		assertThat(this.api.get(TestUser.randomAdmin("usage-admin"), "/api/admin/usage?days=1")).hasStatusOk()
			.bodyJson()
			.satisfies(json -> json.assertThat()
				.extractingPath("$.workspaces[?(@.workspaceId == '" + this.workspaceId + "')].name")
				.asArray()
				.containsExactly((Object) null));
	}

	/** An answer that needed no model call (nothing relevant found) costs nothing. */
	@Test
	void answersWithoutAModelCallAreNotCharged() {
		UUID conversationId = this.api.createConversation(this.owner, this.workspaceId);
		this.api.askAndRead(this.owner, this.workspaceId, conversationId, "zebra quantum xylophone");

		assertThat(this.api.get(this.owner, usageUri())).bodyJson().extractingPath("$.totals.answers").isEqualTo(0);
	}

	@Test
	void periodIsValidated() {
		assertThat(this.api.get(this.owner, usageUri() + "?days=0")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
		assertThat(this.api.get(this.owner, usageUri() + "?days=7")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.daily.length()")
			.isEqualTo(7);
	}

	private UUID ask(TestUser user) {
		UUID conversationId = this.api.createConversation(user, this.workspaceId);
		this.api.askAndRead(user, this.workspaceId, conversationId, "How many vacation days do employees receive?");
		return conversationId;
	}

	private String usageUri() {
		return "/api/workspaces/" + this.workspaceId + "/usage";
	}

}
