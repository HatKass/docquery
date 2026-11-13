package dev.docquery.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.kafka.KafkaContainer;

import dev.docquery.api.support.ApiIntegrationTest;
import dev.docquery.api.support.SampleFiles;
import dev.docquery.api.support.TestUser;
import dev.docquery.api.support.TopicRecorder;

/**
 * The admin API: only platform admins (Keycloak realm role) get in; they see pipeline failures and
 * dead-lettered events across workspaces and can retry them.
 */
class AdminApiTests extends ApiIntegrationTest {

	static final String DLT = "document.uploaded.dlt";

	/** The message Spring Kafka's listener wrapper produces in the worker. */
	static final String WRAPPED = "Listener method 'void dev.docquery.worker.ingestion.DocumentUploadedListener"
			+ ".onDocumentUploaded(org.apache.kafka.clients.consumer.ConsumerRecord<java.lang.String, java.lang.String>)'"
			+ " threw exception; Connection refused";

	@Autowired
	JdbcClient jdbc;

	@Autowired
	KafkaTemplate<String, String> kafka;

	@Autowired
	KafkaContainer kafkaContainer;

	TestUser admin;

	TestUser owner;

	UUID workspaceId;

	UUID documentId;

	@BeforeEach
	void setUp() {
		this.admin = TestUser.randomAdmin("admin");
		this.api.signIn(this.admin);
		this.owner = TestUser.random("admin-test-owner");
		this.api.signIn(this.owner);
		this.workspaceId = this.api.createWorkspace(this.owner, "Pipeline");
		this.documentId = this.api.uploadDocument(this.owner, this.workspaceId, "broken.pdf", SampleFiles.pdf());
	}

	static Stream<String[]> adminEndpoints() {
		String id = UUID.randomUUID().toString();
		return Stream.of(new String[] { "GET", "/api/admin/overview", null },
				new String[] { "GET", "/api/admin/usage", null }, new String[] { "GET", "/api/admin/failures", null },
				new String[] { "POST", "/api/admin/documents/" + id + "/reindex", null },
				new String[] { "GET", "/api/admin/dead-letters", null },
				new String[] { "POST", "/api/admin/dead-letters/" + id + "/retry", null },
				new String[] { "POST", "/api/admin/dead-letters/" + id + "/dismiss", null },
				new String[] { "GET", "/api/admin/users", null },
				new String[] { "PUT", "/api/admin/users/" + id + "/workspace-limit", "{\"limit\":10}" });
	}

	/** Workspace OWNERs are not platform admins. */
	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("adminEndpoints")
	void nonAdminsGet403(String method, String uri, String body) {
		var request = this.mvc.method(HttpMethod.valueOf(method)).uri(uri).with(this.owner.auth());
		if (body != null) {
			request = request.contentType(MediaType.APPLICATION_JSON).content(body);
		}

		assertThat(request.exchange()).hasStatus(HttpStatus.FORBIDDEN)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:access-denied");
	}

	@Test
	void meTellsTheSpaWhoIsAnAdmin() {
		assertThat(this.api.get(this.admin, "/api/me")).bodyJson().extractingPath("$.admin").isEqualTo(true);
		assertThat(this.api.get(this.owner, "/api/me")).bodyJson().extractingPath("$.admin").isEqualTo(false);
	}

	@Test
	void adminsAreNotMembersOfEveryWorkspace() {
		// Admin rights are for operating the platform, not for reading tenants' documents.
		assertThat(this.api.get(this.admin, "/api/workspaces/" + this.workspaceId + "/documents"))
			.hasStatus(HttpStatus.NOT_FOUND);
	}

	@Test
	void overviewCountsDocumentsByStatus() {
		assertThat(this.api.get(this.admin, "/api/admin/overview")).hasStatusOk().bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.documentsByStatus.UPLOADED").asNumber().satisfies(n -> assertThat(n.intValue()).isPositive());
			json.assertThat().extractingPath("$.documentsByStatus.FAILED").isNotNull();
			json.assertThat().extractingPath("$.defaultWorkspaceLimit").isEqualTo(3);
		});
	}

	@Test
	void failedDocumentsOfAllWorkspacesAreListedAndCanBeRetried() {
		markFailed(this.documentId, "The file is damaged.");

		assertThat(this.api.get(this.admin, "/api/admin/failures?limit=500")).hasStatusOk()
			.bodyJson()
			.satisfies(json -> {
				json.assertThat()
					.extractingPath("$[?(@.documentId == '" + this.documentId + "')].failureReason")
					.asArray()
					.containsExactly("The file is damaged.");
				json.assertThat()
					.extractingPath("$[?(@.documentId == '" + this.documentId + "')].workspaceName")
					.asArray()
					.containsExactly("Pipeline");
			});

		assertThat(this.api.post(this.admin, "/api/admin/documents/" + this.documentId + "/reindex", "{}"))
			.hasStatus(HttpStatus.ACCEPTED)
			.bodyJson()
			.extractingPath("$.status")
			.isEqualTo("UPLOADED");
		awaitReindexEvent(this.documentId);
	}

	@Test
	void deadLetteredEventsAreRecordedOnceAndCanBeRetried() {
		markFailed(this.documentId, "The embedding service was unavailable.");
		ProducerRecord<String, String> record = deadLetter(this.documentId, this.workspaceId);
		this.kafka.send(record).join();
		this.kafka.send(deadLetter(this.documentId, this.workspaceId)).join();

		UUID id = awaitDeadLetters(this.documentId, 2).getFirst();
		assertThat(this.api.get(this.admin, "/api/admin/dead-letters?limit=500")).hasStatusOk().bodyJson().satisfies(json -> {
			String match = "$[?(@.id == '" + id + "')]";
			json.assertThat().extractingPath(match + ".documentName").asArray().containsExactly("broken.pdf");
			json.assertThat().extractingPath(match + ".workspaceName").asArray().containsExactly("Pipeline");
			json.assertThat().extractingPath(match + ".documentStatus").asArray().containsExactly("FAILED");
			// The list shows the root cause; the full wrapped message stays available for the details view.
			json.assertThat().extractingPath(match + ".errorSummary").asArray().containsExactly("Connection refused");
			json.assertThat().extractingPath(match + ".exceptionMessage").asArray().containsExactly(WRAPPED);
			json.assertThat().extractingPath(match + ".originalTopic").asArray().containsExactly("document.uploaded");
			json.assertThat().extractingPath(match + ".originalOffset").asArray().containsExactly(42);
			json.assertThat().extractingPath(match + ".retryable").asArray().containsExactly(true);
		});

		MvcTestResult retried = this.api.post(this.admin, "/api/admin/dead-letters/" + id + "/retry", "{}");

		assertThat(retried).hasStatus(HttpStatus.ACCEPTED).bodyJson().satisfies(json -> {
			json.assertThat().extractingPath("$.status").isEqualTo("RETRIED");
			json.assertThat().extractingPath("$.documentStatus").isEqualTo("UPLOADED");
			json.assertThat().extractingPath("$.resolvedBy").isEqualTo("Test " + this.admin.username());
		});
		awaitReindexEvent(this.documentId);
		// Resolved events cannot be retried again, and leave the default (pending) list.
		assertThat(this.api.post(this.admin, "/api/admin/dead-letters/" + id + "/retry", "{}"))
			.hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.type")
			.isEqualTo("urn:docquery:problem:dead-letter-resolved");
		assertThat(this.api.get(this.admin, "/api/admin/dead-letters?limit=500")).bodyJson()
			.extractingPath("$[?(@.id == '" + id + "')]")
			.asArray()
			.isEmpty();
	}

	/** The same Kafka record delivered twice (consumer restart) is stored once. */
	@Test
	void redeliveredRecordsAreStoredOnce() {
		this.kafka.send(deadLetter(this.documentId, this.workspaceId)).join();
		List<UUID> ids = awaitDeadLetters(this.documentId, 1);
		var stored = this.jdbc.sql("SELECT dlt_topic, dlt_partition, dlt_offset FROM dead_letter_event WHERE id = :id")
			.param("id", ids.getFirst())
			.query()
			.singleRow();

		int inserted = this.jdbc.sql("""
				INSERT INTO dead_letter_event (dlt_topic, dlt_partition, dlt_offset, failed_at)
				VALUES (:t, :p, :o, now()) ON CONFLICT DO NOTHING
				""").param("t", stored.get("dlt_topic")).param("p", stored.get("dlt_partition"))
			.param("o", stored.get("dlt_offset"))
			.update();

		assertThat(inserted).isZero();
	}

	@Test
	void eventsOfDeletedDocumentsAndUnreadablePayloadsCanOnlyBeDismissed() {
		this.kafka.send(deadLetter(this.documentId, this.workspaceId)).join();
		UUID deleted = awaitDeadLetters(this.documentId, 1).getFirst();
		this.api.delete(this.owner, "/api/workspaces/" + this.workspaceId + "/documents/" + this.documentId);
		String garbageKey = "garbage-" + UUID.randomUUID();
		this.kafka.send(new ProducerRecord<>(DLT, garbageKey, "not json")).join();
		UUID garbage = Awaitility.await()
			.atMost(Duration.ofSeconds(30))
			.until(() -> this.jdbc.sql("SELECT id FROM dead_letter_event WHERE message_key = :k")
				.param("k", garbageKey)
				.query(UUID.class)
				.optional(), java.util.Optional::isPresent)
			.orElseThrow();

		for (UUID id : List.of(deleted, garbage)) {
			assertThat(this.api.post(this.admin, "/api/admin/dead-letters/" + id + "/retry", "{}"))
				.hasStatus(HttpStatus.CONFLICT)
				.bodyJson()
				.extractingPath("$.type")
				.isEqualTo("urn:docquery:problem:dead-letter-not-retryable");
			assertThat(this.api.post(this.admin, "/api/admin/dead-letters/" + id + "/dismiss", "{}")).hasStatusOk()
				.bodyJson()
				.extractingPath("$.status")
				.isEqualTo("DISMISSED");
		}
		assertThat(this.api.get(this.admin, "/api/admin/dead-letters?status=DISMISSED&limit=500")).bodyJson()
			.extractingPath("$[?(@.id == '" + garbage + "')].payload")
			.asArray()
			.containsExactly("not json");
		assertThat(this.api.get(this.admin, "/api/admin/dead-letters?status=bogus"))
			.hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
	}

	@Test
	void usersCanBeSearched() {
		assertThat(this.api.get(this.admin, "/api/admin/users?q=" + this.owner.username().toUpperCase()))
			.hasStatusOk()
			.bodyJson()
			.satisfies(json -> {
				json.assertThat().extractingPath("$.length()").isEqualTo(1);
				json.assertThat().extractingPath("$[0].workspacesCreated").isEqualTo(1);
				json.assertThat().extractingPath("$[0].workspaceLimit").isEqualTo(3);
				json.assertThat().extractingPath("$[0].workspaceLimitOverride").isNull();
				json.assertThat().extractingPath("$[0].uploadsToday").isEqualTo(1);
			});
		// LIKE wildcards in the query are literal.
		assertThat(this.api.get(this.admin, "/api/admin/users?q=%25")).bodyJson().extractingPath("$.length()").isEqualTo(0);
	}

	private void markFailed(UUID documentId, String reason) {
		this.jdbc.sql("UPDATE document SET status = 'FAILED', failure_reason = :reason WHERE id = :id")
			.param("reason", reason)
			.param("id", documentId)
			.update();
	}

	/** A record as the worker's DeadLetterPublishingRecoverer writes it. */
	private static ProducerRecord<String, String> deadLetter(UUID documentId, UUID workspaceId) {
		String payload = """
				{"eventId":"%s","documentId":"%s","workspaceId":"%s","storageKey":"k","contentType":"application/pdf",
				 "filename":"broken.pdf","reason":"UPLOAD","occurredAt":"2026-11-13T10:00:00Z"}
				""".formatted(UUID.randomUUID(), documentId, workspaceId);
		ProducerRecord<String, String> record = new ProducerRecord<>(DLT, documentId.toString(), payload);
		record.headers()
			.add(KafkaHeaders.DLT_ORIGINAL_TOPIC, "document.uploaded".getBytes(StandardCharsets.UTF_8))
			.add(KafkaHeaders.DLT_ORIGINAL_PARTITION, ByteBuffer.allocate(4).putInt(1).array())
			.add(KafkaHeaders.DLT_ORIGINAL_OFFSET, ByteBuffer.allocate(8).putLong(42).array())
			.add(KafkaHeaders.DLT_EXCEPTION_FQCN,
					"org.springframework.kafka.listener.ListenerExecutionFailedException".getBytes(StandardCharsets.UTF_8))
			.add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, WRAPPED.getBytes(StandardCharsets.UTF_8));
		return record;
	}

	private List<UUID> awaitDeadLetters(UUID documentId, int count) {
		return Awaitility.await()
			.atMost(Duration.ofSeconds(30))
			.until(() -> this.jdbc.sql("SELECT id FROM dead_letter_event WHERE document_id = :doc ORDER BY dlt_offset")
				.param("doc", documentId)
				.query(UUID.class)
				.list(), ids -> ids.size() >= count);
	}

	/** The retry queued a new document.uploaded event (a new event id, reason REINDEX). */
	private void awaitReindexEvent(UUID documentId) {
		try (TopicRecorder recorder = new TopicRecorder(this.kafkaContainer.getBootstrapServers(), "document.uploaded")) {
			recorder.await("document.uploaded", documentId.toString(),
					records -> records.stream()
						.anyMatch(r -> "REINDEX".equals(this.json.readTree(r.value()).get("reason").asString())));
		}
	}

}
