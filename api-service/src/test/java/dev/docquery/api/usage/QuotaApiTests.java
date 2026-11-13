package dev.docquery.api.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.ChunkFixtures;
import dev.docquery.api.support.SampleFiles;
import dev.docquery.api.support.StubChatModel;
import dev.docquery.api.support.TestUser;

/**
 * Quotas from docquery.usage.quotas (defaults: 3 workspaces per user, 50 questions and 20 uploads per
 * user per UTC day, 100 documents per workspace). Daily counters are pre-filled through the database
 * so the tests do not have to ask 50 questions to reach a limit.
 */
class QuotaApiTests extends ApiIntegrationTest {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	StubChatModel model;

	TestUser user;

	UUID userId;

	@BeforeEach
	void setUp() {
		this.model.reset();
		this.user = TestUser.random("quota");
		this.userId = this.api.signIn(this.user);
	}

	// --- Workspace-creation limit (carry-over note, Phase 6) -------------------------------------

	@Test
	void aUserCanCreateThreeWorkspacesThenGetsAFriendlyError() {
		for (int i = 1; i <= 3; i++) {
			this.api.createWorkspace(this.user, "Workspace " + i);
		}

		MvcTestResult fourth = this.api.post(this.user, "/api/workspaces", "{\"name\":\"One too many\"}");

		assertThat(fourth).hasStatus(HttpStatus.CONFLICT).bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.type").isEqualTo("urn:docquery:problem:workspace-limit-reached");
			json.assertThat().extractingPath("$.limit").isEqualTo(3);
			json.assertThat().extractingPath("$.used").isEqualTo(3);
			json.assertThat()
				.extractingPath("$.detail")
				.asString()
				.contains("up to 3 workspaces")
				.contains("ask an administrator");
		});
		assertThat(this.api.get(this.user, "/api/workspaces")).bodyJson().extractingPath("$.length()").isEqualTo(3);
	}

	@Test
	void deletingAWorkspaceFreesItsSlot() {
		UUID first = this.api.createWorkspace(this.user, "A");
		this.api.createWorkspace(this.user, "B");
		this.api.createWorkspace(this.user, "C");

		assertThat(this.api.delete(this.user, "/api/workspaces/" + first)).hasStatus(HttpStatus.NO_CONTENT);

		this.api.createWorkspace(this.user, "D");
	}

	/** Handing a workspace over and leaving it does not free the slot: the limit cannot be bypassed. */
	@Test
	void workspacesHandedOverStillCountForTheirCreator() {
		TestUser heir = TestUser.random("heir");
		this.api.signIn(heir);
		UUID handedOver = this.api.createWorkspace(this.user, "A");
		this.api.createWorkspace(this.user, "B");
		this.api.createWorkspace(this.user, "C");
		this.api.addMember(this.user, handedOver, heir, "OWNER");
		assertThat(this.api.delete(this.user, "/api/workspaces/" + handedOver + "/members/" + this.userId))
			.hasStatus(HttpStatus.NO_CONTENT);

		assertThat(this.api.post(this.user, "/api/workspaces", "{\"name\":\"D\"}")).hasStatus(HttpStatus.CONFLICT);
		// Being made owner of someone else's workspace does not use up the heir's own allowance.
		assertThat(this.api.get(heir, "/api/me/quotas")).bodyJson().extractingPath("$.workspaces.used").isEqualTo(0);
	}

	@Test
	void anAdminOverrideRaisesOrRemovesTheLimit() {
		TestUser admin = TestUser.randomAdmin("quota-admin");
		for (int i = 1; i <= 3; i++) {
			this.api.createWorkspace(this.user, "Workspace " + i);
		}

		assertThat(this.api.put(admin, "/api/admin/users/" + this.userId + "/workspace-limit", "{\"limit\":4}"))
			.hasStatusOk()
			.bodyJson()
			.satisfies(json -> {
				json.assertThat().extractingPath("$.workspaceLimitOverride").isEqualTo(4);
				json.assertThat().extractingPath("$.workspaceLimit").isEqualTo(4);
				json.assertThat().extractingPath("$.workspacesCreated").isEqualTo(3);
			});
		this.api.createWorkspace(this.user, "Workspace 4");
		assertThat(this.api.post(this.user, "/api/workspaces", "{\"name\":\"5\"}")).hasStatus(HttpStatus.CONFLICT);

		// 0 = no limit.
		assertThat(this.api.put(admin, "/api/admin/users/" + this.userId + "/workspace-limit", "{\"limit\":0}"))
			.hasStatusOk()
			.bodyJson()
			.extractingPath("$.workspaceLimit")
			.isNull();
		this.api.createWorkspace(this.user, "Workspace 5");

		// null = back to the default (3), which the user is now over.
		assertThat(this.api.put(admin, "/api/admin/users/" + this.userId + "/workspace-limit", "{\"limit\":null}"))
			.hasStatusOk()
			.bodyJson()
			.extractingPath("$.workspaceLimit")
			.isEqualTo(3);
		assertThat(this.api.post(this.user, "/api/workspaces", "{\"name\":\"6\"}")).hasStatus(HttpStatus.CONFLICT);
	}

	@Test
	void onlyAdminsCanOverrideLimits() {
		assertThat(this.api.put(this.user, "/api/admin/users/" + this.userId + "/workspace-limit", "{\"limit\":99}"))
			.hasStatus(HttpStatus.FORBIDDEN);
		assertThat(this.api.put(TestUser.randomAdmin("admin"), "/api/admin/users/" + this.userId + "/workspace-limit",
				"{\"limit\":-1}"))
			.hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(this.api.put(TestUser.randomAdmin("admin"), "/api/admin/users/" + UUID.randomUUID() + "/workspace-limit",
				"{\"limit\":5}"))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	// --- Daily question quota --------------------------------------------------------------------

	@Test
	void theLastQuestionOfTheDayIsAnsweredTheNextOneIsRefused() {
		UUID workspaceId = this.api.createWorkspace(this.user, "Questions");
		UUID documentId = this.api.uploadDocument(this.user, workspaceId, "Handbook.pdf", SampleFiles.pdf());
		new ChunkFixtures(this.jdbc).index(workspaceId, documentId, 1, "Employees receive 24 days of paid vacation.");
		UUID conversationId = this.api.createConversation(this.user, workspaceId);
		setCounter("questions", 49);

		this.api.askAndRead(this.user, workspaceId, conversationId, "How many vacation days?");
		MvcTestResult refused = this.api.ask(this.user, workspaceId, conversationId, "And sick days?");

		assertThat(refused).hasStatus(HttpStatus.TOO_MANY_REQUESTS).bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.type").isEqualTo("urn:docquery:problem:daily-question-quota-exceeded");
			json.assertThat().extractingPath("$.limit").isEqualTo(50);
			json.assertThat().extractingPath("$.detail").asString().contains("50 questions today").contains("midnight UTC");
			json.assertThat().extractingPath("$.resetsAt").isNotNull();
		});
		assertThat(refused.getResponse().getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
		// The refused question is not stored and the model was asked only once.
		assertThat(this.api.get(this.user, "/api/workspaces/" + workspaceId + "/conversations/" + conversationId))
			.bodyJson()
			.extractingPath("$.messages.length()")
			.isEqualTo(2);
		assertThat(this.model.prompts()).hasSize(1);
		assertThat(this.api.get(this.user, "/api/me/quotas")).bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.questionsToday.used").isEqualTo(50);
			json.assertThat().extractingPath("$.questionsToday.remaining").isEqualTo(0);
		});
	}

	@Test
	void deletingConversationsDoesNotGiveQuestionsBack() {
		UUID workspaceId = this.api.createWorkspace(this.user, "Questions");
		UUID conversationId = this.api.createConversation(this.user, workspaceId);
		this.api.askAndRead(this.user, workspaceId, conversationId, "Anything?");

		this.api.delete(this.user, "/api/workspaces/" + workspaceId + "/conversations/" + conversationId);

		assertThat(this.api.get(this.user, "/api/me/quotas")).bodyJson()
			.extractingPath("$.questionsToday.used")
			.isEqualTo(1);
	}

	@Test
	void questionsOfAnotherDayDoNotCount() {
		this.jdbc.sql("INSERT INTO daily_usage_counter (user_id, usage_date, questions) VALUES (:u, :d, 50)")
			.param("u", this.userId)
			.param("d", today().minusDays(1))
			.update();
		UUID workspaceId = this.api.createWorkspace(this.user, "Questions");
		UUID conversationId = this.api.createConversation(this.user, workspaceId);

		assertThat(this.api.ask(this.user, workspaceId, conversationId, "Anything?")).hasStatusOk();
	}

	// --- Upload limits ---------------------------------------------------------------------------

	@Test
	void dailyUploadQuotaIsEnforcedAndRejectedUploadsCountNothing() {
		UUID workspaceId = this.api.createWorkspace(this.user, "Uploads");
		setCounter("uploads", 19);
		// A rejected upload (empty file) does not use up the quota.
		assertThat(this.api.upload(this.user, workspaceId, "empty.txt", new byte[0]))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);

		this.api.uploadDocument(this.user, workspaceId, "twentieth.txt", SampleFiles.text("twentieth upload"));
		MvcTestResult refused = this.api.upload(this.user, workspaceId, "21st.txt", SampleFiles.text("one too many"));

		assertThat(refused).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:daily-upload-quota-exceeded");
		assertThat(this.api.get(this.user, "/api/workspaces/" + workspaceId + "/documents")).bodyJson()
			.extractingPath("$.length()")
			.isEqualTo(1);
	}

	@Test
	void aWorkspaceHoldsAtMostTheConfiguredNumberOfDocuments() {
		UUID workspaceId = this.api.createWorkspace(this.user, "Full");
		this.jdbc.sql("""
				INSERT INTO document (workspace_id, filename, content_type, size_bytes, sha256, storage_key, uploaded_by, status)
				SELECT :ws, 'filler-' || n || '.txt', 'text/plain', 1, lpad(to_hex(n), 64, '0'),
				       'test/filler/' || gen_random_uuid(), :u, 'READY'
				  FROM generate_series(1, 100) AS n
				""").param("ws", workspaceId).param("u", this.userId).update();

		MvcTestResult refused = this.api.upload(this.user, workspaceId, "101.txt", SampleFiles.text("no room"));

		assertThat(refused).hasStatus(HttpStatus.CONFLICT).bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.type").isEqualTo("urn:docquery:problem:workspace-document-limit-reached");
			json.assertThat().extractingPath("$.detail").asString().contains("100 documents");
		});
		// Nothing counted against the daily upload quota.
		assertThat(this.api.get(this.user, "/api/me/quotas")).bodyJson()
			.extractingPath("$.uploadsToday.used")
			.isEqualTo(0);
	}

	@Test
	void myQuotasShowTheLimitsInEffect() {
		this.api.createWorkspace(this.user, "One");

		assertThat(this.api.get(this.user, "/api/me/quotas")).hasStatusOk().bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.workspaces.used").isEqualTo(1);
			json.assertThat().extractingPath("$.workspaces.limit").isEqualTo(3);
			json.assertThat().extractingPath("$.workspaces.remaining").isEqualTo(2);
			json.assertThat().extractingPath("$.questionsToday.limit").isEqualTo(50);
			json.assertThat().extractingPath("$.uploadsToday.limit").isEqualTo(20);
			json.assertThat().extractingPath("$.maxDocumentsPerWorkspace").isEqualTo(100);
			json.assertThat().extractingPath("$.resetsAt").asString().endsWith("T00:00:00Z");
		});
	}

	private void setCounter(String column, int value) {
		this.jdbc.sql("INSERT INTO daily_usage_counter (user_id, usage_date, " + column + ") VALUES (:u, :d, :v) "
				+ "ON CONFLICT (user_id, usage_date) DO UPDATE SET " + column + " = :v")
			.param("u", this.userId)
			.param("d", today())
			.param("v", value)
			.update();
	}

	private static LocalDate today() {
		return LocalDate.now(ZoneOffset.UTC);
	}

}
